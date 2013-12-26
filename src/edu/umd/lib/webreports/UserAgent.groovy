package edu.umd.lib.webreports

import java.security.*

import org.dom4j.*
import org.dom4j.io.*

import org.apache.commons.lang.StringUtils

import org.apache.commons.httpclient.*
import org.apache.commons.httpclient.methods.*
import org.apache.commons.httpclient.methods.multipart.*
import org.apache.commons.httpclient.params.*

import org.apache.log4j.Logger


public class UserAgent {
  String id
  String string
  String desc
  List   types
  String comment

  private static List auths = []
  private static File db = new File('data/allagents.xml')
  private static File dbNew = new File('data/allagents-new.xml')

  private static Logger log = Logger.getInstance(UserAgent.class)


  /**
   * Refresh the user-agent database, if it has changed.
   */

  public static downloadDb() {

    log.info("Downloading User-Agent database")

    try {
      log.info("...downloading file")

      // download new file and calculate it's digest
      def client = new HttpClient()
      def method = new GetMethod('http://www.user-agents.org/allagents.xml')

      def status = client.executeMethod(method)

      assert status == HttpStatus.SC_OK

      def digestNew = new DigestInputStream(method.responseStream, MessageDigest.getInstance('MD5'))
      def fNew = new FileOutputStream(dbNew)

      fNew.leftShift(digestNew)

      digestNew.close()
      fNew.close()

      if (db.exists()) {
        // calculate current file's digest
        def digest = new DigestInputStream(new FileInputStream(db), MessageDigest.getInstance('MD5'))
        def b = new byte[1024]
        while (digest.read(b,0,1024) != -1) {}
        digest.close()

        if (digest.messageDigest.digest() == digestNew.messageDigest.digest()) {
          log.info("...file has not changed")
          dbNew.delete()
          return
        }
      }

      // ensure the new file is readable, parseable
      def reader = new SAXReader()
      def doc = reader.read(dbNew)
    }
    catch (Throwable t) {
      log.error("...error downloading new file:\n${t.message}")
      return
    }

    // new file has been successfully downloaded and parsed
    log.info("...installing new file")

    if (db.exists()) {
      db.delete()
    }
    dbNew.renameTo(db)
  }


  /**
   * Read authoritative user agent list, retrieve latest user agent db file
   */

  public static readConfig() {
    readConfig(true)
  }

  /**
   * Read authoritative user agent list 
   * 
   * @param checkUpdate - check for an update to the user agent db file
   */

  public static readConfig(boolean checkUpdate) {
    if (checkUpdate || ! db.canRead()) {
      downloadDb()
    }
    
    def reader = new SAXReader()
    def doc = reader.read(db)
    doc.selectNodes("/user-agents/user-agent").each { node ->
      auths.add(newInstance(node))
    }
  }


  /**
   * Build new instance of UserAgent from <user-agent> node
   */

  public static UserAgent newInstance(Node n) {
    def ua = new UserAgent()

    ua.id = n.selectSingleNode('ID').text
    ua.string = n.selectSingleNode('String').text
    ua.desc = n.selectSingleNode('Description').text
    ua.comment = n.selectSingleNode('Comment').text

    ua.types = n.selectSingleNode('Type').text.split(/ /) as List

    return ua
  }


  /**
   * Compute the match between user agent strings
   */

  public Float getMatch(String ua) {
    def uan = uaNormalize(ua)
    def authn = uaNormalize(this.string)

    def d = StringUtils.getLevenshteinDistance(uan,authn)

    return (float)d / (float)uan.length()
  }


  /**
   * Get the best match against the entire database.
   *
   * @return List; [0] = match, [1] = UserAgent
   */

  private static Map bestMatchCache = [:]
  public static List getBestMatch(String ua) {
    if (! bestMatchCache.containsKey(ua)) {
      def ret = [null,null]  // [match, UserAgent]

      // Iterate over auths
      for (auth in auths) {
        Float f = auth.getMatch(ua)

        if (f == 0.0 || ret[0] == null || f < ret[0]) {
          ret[0] = f
          ret[1] = auth
        }

        if (f == 0.0) {
          break
        }
      }

      bestMatchCache[ua] = ret
    }

    return bestMatchCache[ua]
  }


  /**
   * Determine if the user agent is a browser.
   */

  public static boolean isBrowser(String ua) {
    def m = getBestMatch(ua)
    return 'B' in m[1].types
  }

  /**
   * Normalize a user-agent string.
   */

  private static Map uaNormCache = [:]
  private static String uaNormalize(s) {
    if (! uaNormCache.containsKey(s)) {
      uaNormCache[s] = s
          .toLowerCase()
          .replaceAll(/; feed-id=\d+/,'')
          .replaceAll(/^.*compatible;+/,'')
    }

    return uaNormCache[s]
  }

}
