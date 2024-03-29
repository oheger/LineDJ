package de.oliver_heger.linedj.archivehttpstart.app

import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import org.apache.commons.configuration.Configuration

/**
  * A helper class for generating configuration data for the archive startup
  * application.
  *
  * This functionality is required by multiple test classes. Therefore, it has
  * been extracted into a separate test helper class.
  */
object StartupConfigTestHelper:
  /** The prefix key for configuration settings about archives. */
  val KeyArchives = "media.archives.archive"

  /** The test chunk size for download operations. */
  val DownloadChunkSize = 8888

  /**
    * Generates the name of a test archive.
    *
    * @param idx the index of the archive
    * @return the name of this archive
    */
  def archiveName(idx: Int): String = "Http Archive Test Name " + idx

  /**
    * Generates the URI of a test archive.
    *
    * @param idx the index of the archive
    * @return the URI of this archive
    */
  def archiveUri(idx: Int): String = s"https://test-archive$idx.org/index.json"

  /**
    * Generates the name of a realm based on an index.
    *
    * @param idx the index of the test realm
    * @return a name for this realm
    */
  def realmName(idx: Int): String = "realm_" + idx

  /**
    * Generates a short name for an HTTP archive.
    *
    * @param idx the index of the archive
    * @return the short name
    */
  def shortName(idx: Int): String =
    val prefix = "Http+Archive+Tes"
    if idx > 1 then prefix + (idx - 1) else prefix

  /**
    * Allows adding a complex structure to the given configuration.
    *
    * @param c         the configuration
    * @param keyPrefix the key prefix for all properties
    * @param props     a map with key-value pairs to be added
    * @return the configuration
    */
  def addToConfig(c: Configuration, keyPrefix: String, props: Map[String, Any]): Configuration =
    val propsList = props.toList
    val pair1 = propsList.head
    val prefix = keyPrefix + "."
    c.addProperty(keyPrefix + "(-1)." + pair1._1, pair1._2)
    propsList.tail foreach { p =>
      c.addProperty(prefix + p._1, p._2)
    }
    c

  /**
    * Adds properties for a test HTTP archive to the specified configuration.
    *
    * @param c         the configuration
    * @param idx       the index of the test archive
    * @param realm     option for the name of the realm
    * @param protocol  option for the HTTP protocol of the archive
    * @param encrypted the encrypted flag for the archive
    * @return the updated configuration
    */
  def addArchiveToConfig(c: Configuration, idx: Int, realm: Option[String] = None,
                         protocol: Option[String] = None, encrypted: Boolean = false):
  Configuration =
    c.addProperty("media.mediaArchive." + DownloadConfig.PropDownloadChunkSize, DownloadChunkSize)
    val propsBase = Map(HttpArchiveStartupConfig.PropArchiveName -> archiveName(idx),
      HttpArchiveStartupConfig.PropArchiveUri -> archiveUri(idx),
      HttpArchiveStartupConfig.PropDownloadBufferSize -> 16384,
      HttpArchiveStartupConfig.PropDownloadMaxInactivity -> 5 * 60,
      HttpArchiveStartupConfig.PropTimeoutReadSize -> 128 * 1024,
      "realm" -> realm.getOrElse(realmName(idx)))
    val propsProto = protocol.fold(propsBase)(p => propsBase + ("protocol" -> p))
    val propsEnc = if encrypted then propsProto + ("encrypted" -> true) else propsProto
    addToConfig(c, KeyArchives, propsEnc)

  /**
    * Adds a number of properties for a set of HTTP archives to the given
    * configuration. For each archive in the specified range test properties
    * are added.
    *
    * @param c       the configuration
    * @param fromIdx the start index
    * @param toIdx   the end index (inclusive)
    * @return the test configuration
    */
  def addConfigs(c: Configuration, fromIdx: Int, toIdx: Int): Configuration =
    (fromIdx to toIdx).foldLeft(c)((c, i) => addArchiveToConfig(c, i))
