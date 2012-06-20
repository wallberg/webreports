package edu.umd.lib.webreports

import groovy.io.FileVisitResult;

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

@Grab('log4j:log4j:1.2.16')
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
class Usage {

  static Logger log = Logger.getInstance(Usage.class)

  // statistics
  static Map stat = [
    'dirs':0l,
    'dirfiles':0l,
    'files':0l,
    'lines':0l,
    'errors':0l,
    'rows':0l,
    'matched':0l,
    'fileonly':0l,
    'urlonly':0l,
  ]

  static List logFileNames = null
  static File dir = null
  static File csv = null

  static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd")

  public static void main(args) {
    try {

      // Get command line options
      parseCommandLine(args)

      // Get system properties
      Properties p = System.properties

      // Initialize UserAgent
      UserAgent.readConfig(false)

      // directory of web files
      if (dir != null) {
        processDir()
      }

      // apache log files
      processLogFiles()

      // write output file
      if (csv != null) {
        log.info("writing $csv")
        PrintWriter w = new PrintWriter(new FileOutputStream(csv))

        // header
        w.println('"Match Type","File","Last Modified","URL","Total Hits","Browser Hits","Bot Hits"')

        Entry.entries.each { k,e ->
          log.debug(e)
          List row = e.csvRow
          w.println(row.collect { v -> '"' + v.replaceAll("\"","\"\"") + '"'}.join(','))

          stat[row[0]]++
          stat.rows++
        }

        w.close()
      }
    }
    catch (Exception e) {
      e.printStackTrace();
      System.exit(1)
    }
    finally {
      println """
Statistics:

  directory:
    subdirs:      ${stat.dirs}
    files:        ${stat.dirfiles}

  log files
    files read:   ${stat.files} 
    lines read:   ${stat.lines}
    parse errors: ${stat.errors}

  csv outfile
    total rows:   ${stat.rows}
    matched:      ${stat.matched}
    file only:    ${stat.fileonly}
    url only:     ${stat.urlonly}
"""
    }

    System.exit(0)
  }

  static final Set ignoreDirs = ['_baks','_notes','.DS_Store'] as Set
  static final Set ignoreFiles = [] as Set

  /**
   * Traverse files in the directory
   */
  public static void processDir() {
    log.info("traversing $dir")

    dir.traverse(
        sort:   { a,b -> a.name<=>b.name },
        preDir: { if (it.name in ignoreDirs) return FileVisitResult.SKIP_SUBTREE },
        postDir: { stat.dirs++ },
        ) { file ->

          if (! file.isDirectory()) {
            if (file.name in ignoreFiles) {
              return FileVisitResult.CONTINUE
            } else {
              stat.dirfiles++
            }
          }

          Entry e = new Entry()
          e.file = file

          Entry.add(file)

          return FileVisitResult.CONTINUE
        }
  }

  /**
   * Read log lines from each log file.
   */

  final static Pattern parser = ~/^([^ ]+?) ([^ ]+?) ([^ ]+?) \[(.*?)\] "(?:[A-Z]+? )?(.+?)(?: .+?)?(?<!\\)" (\d{3}) ([^ ]+) "(.*?)(?<!\\)" "(.*?)(?<!\\)"$/
  final static Pattern ignoreUrls = ~/^\/(archivesum|blogs|cgi-bin|digital|drum)/

  public static void processLogFiles() {
    // Process each log file
    logFileNames.each { fileName ->
      File f = new File(fileName)

      if (! f.canRead()) {
        log.warn("Unable to read ${f}")
      } else {
        stat.files++

        log.info("reading $f")

        // open file for buffered reading
        InputStream is = new FileInputStream(f)
        if (f.name.endsWith(".gz")) {
          is = new GZIPInputStream(is)
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"))

        // process each log file line
        r.eachLine { line ->
          stat.lines++

          Matcher m = parser.matcher(line)

          if (! m.matches()) {
            log.error("line doesn't match: ${line}")
            stat.errors++
          } else {

            def (all,host,foo0,foo1,date,url,code,bytes,referer,ua) = m[0]

            if (! (ignoreUrls.matcher(url).find())) {
              // ua fixup
              ua = ua.toLowerCase()

              // url fixup
              String normUrl = url
                  .replaceAll(/\?.*$/,'')
              //                  .toLowerCase()

              //              // filter
              //              if (! reject(url, code, ua)) {
              //                dst.write(l)
              //                dst.write("\n")
              //                count.write++
              //              } else if (rej) {
              //                rej.write(l)
              //                rej.write("\n")
              //              }

              Entry.add(url, code, UserAgent.isBrowser(ua))
            }
          }
        }

        r.close()

      }
    }
  }


  /*
   * Parse the command line.
   */

  static void parseCommandLine(args) {
    // Setup the options
    Options options = new Options()

    Option option = new Option("i", "directory", true, "input directory")
    option.setRequired(false)
    options.addOption(option)

    option = new Option("o", "outfile", true, "output CSV file")
    option.setRequired(false)
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
    logFileNames = cmd.getArgList()

    if (cmd.hasOption('i')) {
      dir = new File(cmd.getOptionValue('i'))
      if (!dir.isDirectory() || !dir.canRead()) {
        printUsage(options, "Unable to open directory '$dir' for reading");
      }
    }

    if (cmd.hasOption('o')) {
      csv = new File(cmd.getOptionValue('o'))
    }
  }

  /*
   * Print program usage and exit.
   */

  static void printUsage(Options options, Object[] args) {
    // print messages
    args.each {println it}
    if (args.size() != 0) { println '' }

    HelpFormatter formatter = new HelpFormatter()
    formatter.printHelp("getLogs [-i <directory>] [-o <outfile>] [-h] <apache log>...\n", options)

    System.exit(1)
  }

  static class Entry {

    static Map entries = new TreeMap()

    String key = null
    File file = null
    String url = null
    String result = null
    int browser = 0  // number of browser hits
    int bot = 0      // number of bot hits

    static void add(File file) {
      Entry e = new Entry()
      e.key = file.absolutePath.replace('/www','').toLowerCase()
      e.file = file
      entries[e.key] = e
    }

    static void add(String url, String result, isBrowser) {
      int n = url.indexOf('?')
      if (n != -1) {
        url = url.substring(0,n)
      }

      String key = url.toLowerCase()
      if (key.endsWith('/')) {
        key += "index.html"
      }

      Entry e
      if (key in entries) {
        e = entries[key]
      } else {
        e = new Entry()
        e.key = key
        entries[key] = e
      }

      if (e.url == null) {
        e.url = url
        e.result = result
      }

      if (isBrowser) {
        e.browser++
      } else {
        e.bot++
      }
    }

    /**
     * Get a row for CSV file.
     */
    public List getCsvRow() {
      return [
        ((file != null) ? (url != null ? "matched" : "fileonly") : "urlonly"),
        (file == null ? "" : file.absolutePath.replace('/www','')),
        (file == null ? "" : df.format(new Date(file.lastModified()))),
        url ?: "",
        "${browser + bot}",
        "$browser",
        "$bot",
      ]
    }

    public String toString() {
      final String lastModified = (file == null ? "" : df.format(new Date(file.lastModified())))
      return "Key: $key, File: $file ($lastModified), URL: $url ($result), hits: ${browser}/$bot"
    }
  }
}
