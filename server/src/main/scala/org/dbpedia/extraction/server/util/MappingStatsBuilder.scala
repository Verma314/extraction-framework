package org.dbpedia.extraction.server.util

import java.util.logging.Logger
import io.Source
import java.lang.IllegalArgumentException
import org.dbpedia.extraction.wikiparser.impl.wikipedia.Namespaces
import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.mappings._
import org.dbpedia.extraction.util.{WikiUtil, Language}
import scala.Serializable
import scala.collection
import scala.collection.mutable
import java.io._
import org.dbpedia.extraction.ontology.OntologyNamespaces
import org.dbpedia.extraction.destinations.{DBpediaDatasets,Dataset}
import org.dbpedia.extraction.server.util.CreateMappingStats._
import java.net.{URLDecoder, URLEncoder}
import org.dbpedia.extraction.server.util.StringUtils.prettyMillis

class MappingStatsBuilder(statsDir : File, language: Language)
extends MappingStatsConfig(statsDir, language)
{
    private val logger = Logger.getLogger(getClass.getName)

    private val resourceNamespacePrefix = OntologyNamespaces.getResource("", language)

    private val ObjectPropertyTripleRegex = """<([^>]+)> <([^>]+)> <([^>]+)> \.""".r
    private val DatatypePropertyTripleRegex = """<([^>]+)> <([^>]+)> "(.*)"\S* \.""".r
    
    def buildStats(redirectsFile: File, infoboxPropsFile: File, templParamsFile: File, paramsUsageFile: File): Unit =
    {
        var templatesMap = new mutable.HashMap[String, TemplateStats]()
        
        println("Reading redirects from " + redirectsFile)
        val redirects = loadTemplateRedirects(redirectsFile)
        println("Found " + redirects.size + " redirects")
        
        println("Using Template namespace prefix " + templateNamespacePrefix + " for language " + language.wikiCode)
        
        println("Counting templates in " + infoboxPropsFile)
        countTemplates(infoboxPropsFile, templatesMap, redirects)
        println("Found " + templatesMap.size + " different templates")


        println("Loading property definitions from " + templParamsFile)
        propertyDefinitions(templParamsFile, templatesMap, redirects)

        println("Counting properties in " + paramsUsageFile)
        countProperties(paramsUsageFile, templatesMap, redirects)

        val wikiStats = new WikipediaStats(language, redirects, templatesMap)
        
        logger.info("Serializing "+language.wikiCode+" wiki statistics to " + mappingStatsFile)
        val output = new OutputStreamWriter(new FileOutputStream(mappingStatsFile), "UTF-8")
        try wikiStats.write(output) finally output.close()
    }

    private def stripUri(uri: String): String =
    {
        if (! uri.startsWith(resourceNamespacePrefix)) throw new Exception(uri)
        WikiUtil.wikiDecode(uri.substring(resourceNamespacePrefix.length), language, capitalize=false)
    }
    
    private def eachLine(file: File)(process: String => Unit) : Unit = {
        val millis = System.currentTimeMillis
        var count = 0
        val source = Source.fromFile(file, "UTF-8")
        try {
            for (line <- source.getLines()) {
                process(line)
                count += 1
                if (count % 10000 == 0) print(count+" lines\r")
            }
        } finally source.close
        println(count+" lines - "+prettyMillis(System.currentTimeMillis - millis))
    }

    private def loadTemplateRedirects(file: File): mutable.Map[String, String] =
    {
        val redirects = new mutable.HashMap[String, String]()
        eachLine(file) {
            line => line match {
                case ObjectPropertyTripleRegex(subj, pred, obj) => {
                    val templateName = cleanUri(subj)
                    redirects(templateName) = cleanUri(obj)
                }
                case _ => if (line.nonEmpty) throw new IllegalArgumentException("line did not match object property triple syntax: " + line)
            }
        }
        
        println("Resolving "+redirects.size+" redirects")
        // resolve transitive closure
        for ((source, target) <- redirects)
        {
            var cyclePrevention: Set[String] = Set()
            var closure = target
            while (redirects.contains(closure) && !cyclePrevention.contains(closure))
            {
                closure = redirects.get(closure).get
                cyclePrevention += closure
            }
            redirects(source) = closure
        }

        redirects
    }
    
    // TODO: use this instead of regex, may be faster
    private def splitObjectLine(line : String) : (String, String, String) = {
      if (! line.startsWith("<")) throw new Exception(line)
      val subEnd = line.indexOf('>')
      if (subEnd == -1) throw new Exception(line)
      val preStart = line.indexOf('<', subEnd)
      if (preStart == -1) throw new Exception(line)
      val preEnd = line.indexOf('>', preStart)
      if (preEnd == -1) throw new Exception(line)
      val obStart = line.indexOf('<', preEnd)
      if (obStart == -1) throw new Exception(line)
      val obEnd = line.indexOf('>', obStart)
      if (obEnd == -1) throw new Exception(line)
      return (line.substring(1, subEnd), line.substring(preStart + 1, preEnd), line.substring(obStart + 1, obEnd))
    }

    /**
     * @param fileName name of file generated by InfoboxExtractor, e.g. infobox_properties_en.nt
     */
    private def countTemplates(file: File, resultMap: mutable.Map[String, TemplateStats], redirects: mutable.Map[String, String]): Unit =
    {
        // iterate through infobox properties
        eachLine(file) {
            line => line match {
                // if there is a wikiPageUsesTemplate relation
                case ObjectPropertyTripleRegex(subj, pred, obj) => if (unescapeNtriple(pred) contains "wikiPageUsesTemplate")
                {
                    var templateName = cleanUri(obj)
                    
                    // resolve redirect for *object*
                    templateName = redirects.getOrElse(templateName, templateName)

                    // lookup the *object* in the resultMap, create a new TemplateStats object if not found,
                    // and increment templateCount
                    resultMap.getOrElseUpdate(templateName, new TemplateStats).templateCount += 1
                }
                case DatatypePropertyTripleRegex(_,_,_) => // ignore
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match object property triple syntax: " + line)
            }
        }
    }

    private def propertyDefinitions(file: File, resultMap: mutable.Map[String, TemplateStats], redirects: mutable.Map[String, String]): Unit =
    {
        // iterate through template parameters
        eachLine(file) {
            line => line match {
                case DatatypePropertyTripleRegex(subj, pred, obj) =>
                {
                    var templateName = cleanUri(subj)
                    val propertyName = cleanValue(obj)
                    
                    // resolve redirect for *subject*
                    templateName = redirects.getOrElse(templateName, templateName)

                    // lookup the *subject* in the resultMap
                    //skip the templates that are not found (they don't occur in Wikipedia)
                    for (stats <- resultMap.get(templateName))
                    {
                        // add object to properties map with count 0
                        stats.properties.put(propertyName, 0)
                    }
                }
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match datatype property triple syntax: " + line)
                case _ =>
            }
        }
    }

    private def countProperties(file: File, resultMap: mutable.Map[String, TemplateStats], redirects: mutable.Map[String, String]) : Unit =
    {
        // iterate through infobox test
        eachLine(file) {
            line => line match {
                case DatatypePropertyTripleRegex(subj, pred, obj) => {
                    var templateName = cleanUri(pred)
                    val propertyName = cleanValue(obj)
                    
                    // resolve redirect for *predicate*
                    templateName = redirects.getOrElse(templateName, templateName)

                    // lookup the *predicate* in the resultMap
                    // skip the templates that are not found (they don't occur in Wikipedia)
                    for(stats <- resultMap.get(templateName)) {
                        // lookup *object* in the properties map
                        //skip the properties that are not found with any count (they don't occurr in the template definition)
                        if (stats.properties.contains(propertyName)) {
                            // increment count in properties map
                            stats.properties.put(propertyName, stats.properties(propertyName) + 1)
                        }
                    }
                }
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match datatype property triple syntax: " + line)
                case _ =>
            }
        }
    }
    
    private def cleanUri(uri: String) : String = cleanName(stripUri(unescapeNtriple(uri)))
    
    private def cleanValue(value: String) : String = cleanName(unescapeNtriple(value))
    
    // As of 2012-04-12, some property and template names contain line breaks. They also contain
    // all other kinds of crap like "<noinclude>", "}}", but there is too much dirt to even 
    // start to clean it up. Line breaks mess up our file format, so let's remove them.
    private def cleanName(name: String) : String = name.replaceAll("\r|\n", "")

    private def unescapeNtriple(value: String): String =
    {
        val sb = new java.lang.StringBuilder

        val inputLength = value.length
        var offset = 0

        while (offset < inputLength)
        {
            val c = value.charAt(offset)
            if (c != '\\') sb append c
            else
            {
                offset += 1
                val specialChar = value.charAt(offset)
                specialChar match
                {
                    case '"' => sb append '"'
                    case 't' => sb append '\t'
                    case 'r' => sb append '\r'
                    case '\\' => sb append '\\'
                    case 'n' => sb append '\n'
                    case 'u' =>
                    {
                        offset += 1
                        val codepoint = value.substring(offset, offset + 4)
                        val character = Integer.parseInt(codepoint, 16).asInstanceOf[Char]
                        sb append character
                        offset += 3
                    }
                    case 'U' =>
                    {
                        offset += 1
                        val codepoint = value.substring(offset, offset + 8)
                        val character = Integer.parseInt(codepoint, 16)
                        sb appendCodePoint character
                        offset += 7
                    }
                }
            }
            offset += 1
        }
        sb.toString
    }
}
