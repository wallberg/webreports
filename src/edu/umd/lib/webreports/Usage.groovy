package edu.umd.lib.webreports

import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.regex.Matcher
import java.util.regex.Pattern

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
    'dirs':0,
    'dirfiles':0,
    'files':0,
    'lines':0,
    'errors':0,
    'rows':0,
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
        w.println('"File","Last Modified","URL","Result Code","Total Hits","Browser Hits","Bot Hits"')

        Entry.entries.each { k,e ->
          log.debug(e)

          w.println(e.csvRow.collect { v -> '"' + v.replaceAll("\"","\"\"") + '"'}.join(','))
          
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
    rows:         ${stat.rows}
"""
    }

    System.exit(0)
  }

  /**
   * Traverse files in the directory
   */
  public static void processDir() {
    log.info("traversing $dir")
    
    dir.traverse(sort:{a,b->a.name<=>b.name}) { file ->
      if (file.isDirectory()) {
        stat.dirs++
      } else {
        stat.dirfiles++
      }

      Entry e = new Entry()
      e.file = file

      Entry.add(file)
    }
  }

  /**
   * Read log lines from each log file.
   */

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

          Matcher m = (line =~ /^([^ ]+?) ([^ ]+?) ([^ ]+?) \[(.*?)\] "(?:[A-Z]+? )?(.+?)(?: .+?)?(?<!\\)" (\d{3}) ([^ ]+) "(.*?)(?<!\\)" "(.*?)(?<!\\)"$/)

          if (! m.matches()) {
            log.error("line doesn't match: ${line}")
            count.errors++
          } else {

            def (all,host,foo0,foo1,date,url,code,bytes,referer,ua) = m[0]

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

        r.close()

      }
    }
  }


  /**********************************************************************/
  /*
   * Get a local log files.
   */

  def getFilesLocal() {

    srcdir = new File(srcuri)

    if (! (dstdir.exists() && dstdir.isDirectory())) {
      log.error("destination directory does not exist: ${dstdir}")
      System.exit(1)
    }

    log.info("Source directory: ${srcdir}")

    // Iterate over matching source files
    first = true
    srcdir.traverse(nameFilter:srcpat, maxDepth:0, sort:{a,b->a.name<=>b.name}) { srcfile ->
      count = ['read':0, 'write':0, 'error':0]

      if (first) {
        UserAgent.readConfig()
        first = false
      }

      log.info("Processing ${srcfile.name}")

      dstfile = new File(dstdir, srcfile.name)
      log.info("...copying,filtering to ${dstfile.name}")

      // Read and write gziped file
      src = new InputStreamReader(new GZIPInputStream(new FileInputStream(srcfile)),"UTF-8")
      dst = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(dstfile)),"UTF-8")
      rej = null
      if (rejdir) {
        rejfile = new File(rejdir, srcfile.name)
        rej = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(rejfile)),"UTF-8")
      }

      // Process each line
      src.eachLine { l ->
        count.read++

        // parse the line
        m = (l =~ /^([^ ]+?) ([^ ]+?) ([^ ]+?) \[(.*?)\] "(?:[A-Z]+? )?(.+?)(?: .+?)?(?<!\\)" (\d{3}) ([^ ]+) "(.*?)(?<!\\)" "(.*?)(?<!\\)"$/)

        if (! m.matches()) {
          log.error("line doesn't match: ${l}")
          count.error++
        } else {

          def (all,host,foo0,foo1,date,url,code,bytes,referer,ua) = m[0]

          // ua fixup
          ua = ua.toLowerCase()

          // url fixup
          url = url
              .replaceAll(/\?.*$/,'')
              .toLowerCase()

          // filter
          if (! reject(url, code, ua)) {
            dst.write(l)
            dst.write("\n")
            count.write++
          } else if (rej) {
            rej.write(l)
            rej.write("\n")
          }
        }
      }

      dst.close()
      src.close()
      if (rej) {
        rej.close()
      }

      // Delete the src file
      if (! preserve) {
        srcfile.delete()
        log.info("...deleted ${srcfile.name}")
      }

      log.info("...line counts: ${count}")
    }
  }


  /**********************************************************************/
  /**
   * Reject a line base on url, return code, or user agent.
   */
  def reject(url, code, ua) {

    reUrl = [
      /^\/(images|styles|robots.txt|archivesum|blogs|cgi-bin|dcr|digital|drum|etc\/local\/(emw|coldwar))/,
      /\.(jpg|gif|ico|png|bmp|js|css)$/,
    ]

    // url filter
    for (pat in reUrl) {
      if ((url =~ pat).find()) {
        return true
      }
    }

    // return code filter
    if (! (code =~ /[23]\d\d/)) {
      return true
    }

    // user agent filter
    return ! UserAgent.isBrowser(ua)
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

    static Map entries = [:]

    String key = null
    File file = null
    String url = null
    String result = null
    int browser = 0  // number of browser hits
    int bot = 0      // number of bot hits

    static void add(File file) {
      Entry e = new Entry()
      e.key = file.absolutePath
      e.file = file
      entries[e.key] = e
    }

    static void add(String url, String result, isBrowser) {
      String key = url

      Entry e
      if (key in entries) {
        e = entries[key]
      } else {
        e = new Entry()
        e.key = key
        e.url = url
        e.result = result
        entries[key] = e
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
        (file == null ? "" : file.toString()),
        (file == null ? "" : df.format(new Date(file.lastModified()))),
        url ?: "",
        result ?: "",
        "${browser + bot}",
        "$browser",
        "$bot",
      ]
    }

    public String toString() {
      final String lastModified = (file == null ? "" : df.format(new Date(file.lastModified())))
      return "File: $file ($lastModified), URL: $url ($result), hits: ${browser}/$bot"
    }
  }
}
