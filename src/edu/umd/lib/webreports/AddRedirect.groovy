package edu.umd.lib.webreports

import groovy.io.FileVisitResult;

import java.net.URLDecoder

import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.TreeMap

import java.text.SimpleDateFormat

import org.apache.commons.cli.CommandLine
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import org.apache.commons.cli.PosixParser

import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import org.apache.log4j.ConsoleAppender
import org.apache.log4j.Priority

/**
 * Generate usage for report for /www files combined with urls from apache logs.
 * 
 * Report CSV file fields:
 * <pre>
 *   File
 *   Last Modified
 *   URL*
 *   result code
 *   hits
 *   hits/browser
 *   hits/bot
 * </pre>
 * 
 * @author wallberg
 *
 */
class AddRedirect {

  static Logger log = Logger.getInstance(AddRedirect.class)

  // statistics
  static Map stat = [:].withDefault { v -> 0l }

  static File infile = null
  static File outfile = null

  public static void main(args) {
    try {

      HttpURLConnection.followRedirects = false
      
      // Get command line options
      parseCommandLine(args)

      // Get system properties
      Properties p = System.properties

      BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(infile), "UTF-8"))
      PrintWriter w = new PrintWriter(new FileOutputStream(outfile))

      boolean first = true

      log.info("reading $infile")

      r.eachLine { line ->
        stat.read++

        List row = line.split(/,(?=([^\"]*\"[^\"]*\")*[^\"]*$)/,-1).collect {
          def m = (it =~ /^"?(.*?)"?$/)
          m[0][1]
        }

        String redirectsTo = ''
        if (first) {
          redirectsTo = 'Redirects to'
          first = false
        } else {
          List results = row[4].split(/,/)

          if (results.any { it =~ /3\d\d/ }) {
            stat.redirect++
            redirectsTo =  getRedirectTo(row[3])
          }
        }

        row << redirectsTo.replaceAll("\"","\"\"")

        w.println(row.collect { '"' + it + '"' }.join(','))
      }

      r.close()
      w.close()
    }
    catch (Exception e) {
      e.printStackTrace();
      System.exit(1)
    }
    finally {
      println """
Statistics:

  rows read: ${stat.read}

  redirects found: ${stat.redirect}
  redirects added: ${stat.add}
"""
    }

    System.exit(0)
  }

  /**
   * Check the web server for a redirect value
   */

  static String getRedirectTo(path) {

    try {
      URL url = new URL("http","rhspipeband.org",path)
      log.info("checking $url")
      
      HttpURLConnection h = url.openConnection()
      h.requestMethod = 'HEAD'
      h.connectTimeout = 10000

      h.connect()
      log.info("response: " + h.responseCode)

      if (h.responseCode in (300..399) && h.headerFields.Location) {
        stat.add++
        URL redirectTo = new URL(h.headerFields['Location'][0])
        log.info("redirects to: $redirectTo")
        return redirectTo.path
      }
    }
    catch (Exception e) {
      e.printStackTrace()
    }

    return ''
  }

  /*
   * Parse the command line.
   */

  static void parseCommandLine(args) {
    // Setup the options
    Options options = new Options()

    Option option = new Option("i", "input", true, "input CSV file")
    option.setRequired(true)
    options.addOption(option)

    option = new Option("o", "output", true, "output CSV file")
    option.setRequired(true)
    options.addOption(option)

    // Check for help
    if ("-h" in args) {
      printUsage(options)
    }

    // Parse the command line
    PosixParser parser = new PosixParser()
    CommandLine cmd
    try {
      cmd = parser.parse(options, args)
    }
    catch (Exception e) {
      println e.class.name + ': ' + e.message
      println ''
      printUsage(options)
    }

    // Validate results
    if (cmd.getArgList().size() > 0) {
      printUsage(options)
    }

    infile = new File(cmd.getOptionValue('i'))
    if (!infile.canRead()) {
      printUsage(options, "Unable to open '$infile' for reading");
    }

    outfile = new File(cmd.getOptionValue('o'))
  }

  /*
   * Print program usage and exit.
   */

  static void printUsage(Options options, Object[] args) {
    // print messages
    args.each {println it}
    if (args.size() != 0) { println '' }

    HelpFormatter formatter = new HelpFormatter()
    formatter.printHelp("AddRedirect [-h] -i <input> -o <output>\n", options)

    System.exit(1)
  }

}
