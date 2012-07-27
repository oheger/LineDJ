package de.oliver_heger.splaya.playlist.impl

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.CRC32

import scala.collection.Seq
import scala.xml.Elem
import scala.xml.XML

import org.slf4j.LoggerFactory

import de.oliver_heger.splaya.playlist.PlaylistFileStore

/**
 * A default implementation of the ''PlaylistFileStore'' trait.
 *
 * This implementation stores information about playlist in XML documents in the
 * file system. The directory in which to store these files is specified as
 * constructor argument. The files are named by their playlist ID with default
 * file extensions.
 *
 * For calculating the playlist ID a simple checksum algorithm is used based on
 * the JDK ''CRC32'' class.
 *
 * @param directoryName the name of the directory where to store the data files
 */
class PlaylistFileStoreImpl(val directoryName: String) extends PlaylistFileStore {
  /** Constant for the file extension for playlist files. */
  private val ExtPlaylist = ".plist"

  /** Constant for the file extension for settings files. */
  private val ExtSettings = ".settings"

  /** Constant for the encoding. */
  private val Encoding = "iso-8859-1"

  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[PlaylistFileStoreImpl])

  /** The ''File'' for the data directory. */
  lazy val dataDirectory = fetchDataDirectory(directoryName)

  /**
   * @inheritdoc This implementation generates a CRC32 checksum over the sorted
   * sequence.
   */
  def calculatePlaylistID(playlist: Seq[String]): String = {
    val sortedPlaylist = playlist.sortWith(_ < _)
    val crc = new CRC32
    sortedPlaylist foreach (s => crc.update(s.getBytes))
    java.lang.Long.toHexString(crc.getValue())
  }

  /**
   * @inheritdoc This implementation searches for a file in the data directory
   * with the name of the given playlist ID and the extension ''.plist''.
   */
  def loadPlaylist(playlistID: String): Option[Elem] =
    loadFile(playlistID, ExtPlaylist)

  def savePlaylist(playlistID: String, plElem: Elem) {
    saveFile(playlistID, ExtPlaylist, plElem)
  }

  /**
   * @inheritdoc This implementation searches for a file in the data directory
   * with the name of the given playlist ID and the extension ''.settings''.
   */
  def loadSettings(playlistID: String): Option[Elem] =
    loadFile(playlistID, ExtSettings)

  def saveSettings(playlistID: String, root: Elem) {
    saveFile(playlistID, ExtSettings, root)
  }

  /**
   * Obtains a ''File'' object for the data directory. If the directory does
   * not exist, it is created now.
   * @param directoryName the name of the data directory
   * @return the corresponding ''File'' object
   */
  private def fetchDataDirectory(directoryName: String): File = {
    val dir = new File(directoryName)
    if (!dir.isDirectory && !dir.mkdirs()) {
      throw new IllegalArgumentException("Could not create data directory: "
        + directoryName)
    }
    dir
  }

  /**
   * Generates the ''File'' object for the specified data file.
   * @param playlistID the ID of the playlist
   * @param ext the file extension
   * @return the corresponding ''File'' object
   */
  private def dataFile(playlistID: String, ext: String): File =
    new File(dataDirectory, playlistID + ext)

  /**
   * Helper method for loading an XML data file.
   * @param playlistID the ID of the playlist
   * @param ext the file extension
   * @return an option with the parsed XML element
   */
  private def loadFile(playlistID: String, ext: String): Option[Elem] = {
    val file = dataFile(playlistID, ext)
    if (file.isFile()) {
      log.info("Loading {} file for playlist {}", ext, playlistID)

      var in: Reader = null
      try {
        in = new BufferedReader(new InputStreamReader(new FileInputStream(file),
          Encoding))
        Some(XML.load(in))
      } catch {
        case ex: Exception =>
          log.error("Could not load file", ex)
          None
      } finally {
        close(in)
      }
    } else {
      None
    }
  }

  /**
   * Helper method for saving an XML file in the data directory.
   * @param playlistID the ID of the playlist
   * @param ext the file extension
   * @param root the root XML element
   */
  private def saveFile(playlistID: String, ext: String, root: Elem) {
    val file = dataFile(playlistID, ext)
    XML.save(filename = file.getAbsolutePath, node = root, enc = Encoding,
      xmlDecl = true)
  }

  /**
   * Closes the specified reader ignoring any exceptions.
   */
  private def close(r: Reader) {
    if (r != null) {
      try {
        r.close()
      } catch {
        case ioex: IOException =>
          log.warn("Error when closing reader.", ioex)
      }
    }
  }
}
