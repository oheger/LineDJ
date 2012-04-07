package de.oliver_heger.splaya.playlist.impl

import scala.actors.Actor
import de.oliver_heger.splaya.playlist.FSScanner
import de.oliver_heger.splaya.playlist.PlaylistFileStore
import de.oliver_heger.splaya.playlist.PlaylistGenerator
import scala.xml.Elem
import scala.collection.mutable.ListBuffer
import de.oliver_heger.splaya.engine.AddSourceStream
import de.oliver_heger.splaya.engine.Exit

/**
 * An actor implementing the major part of the functionality required by a
 * [[de.oliver_heger.splaya.playlist.PlaylistController]] implementation.
 *
 * This actor manages the current playlist of the audio player. It uses a
 * [[de.oliver_heger.splaya.playlist.playlist.FSScanner]] object to determine
 * the audio sources available on the current medium. Based on this list a
 * specific playlist is generated with the help of a
 * [[de.oliver_heger.splaya.playlist.playlist.PlaylistGenerator]]. This playlist
 * is then communicated to the audio engine in terms of
 * [[de.oliver_heger.splaya.engine.AddSourceStream]] messages sent to the actor
 * responsible for audio streaming. It lies also in the responsibility of this
 * actor to persist the current state of the playlist, so that playback can be
 * interrupted and resumed later.
 *
 * @param sourceActor the actor to which the playlist has to be communicated
 * @param scanner the ''FSScanner'' for scanning the source medium
 * @param store the ''PlaylistFileStore'' for persisting playlist data
 * @param generator the ''PlaylistGenerator'' for generating a new playlist
 */
private class PlaylistCtrlActor(sourceActor: Actor, scanner: FSScanner,
  store: PlaylistFileStore, generator: PlaylistGenerator) extends Actor {
  /** A sequence with the current playlist. */
  private var playlist: Seq[String] = List.empty

  /** The ID of the current playlist. */
  private var playlistID: String = _

  def act() {
    var running = true

    while (running) {
      receive {
        case ex: Exit =>
          ex.confirmed(this)
          running = false

        case ReadMedium(uri) =>
          handleReadMedium(uri)
      }
    }
  }

  /**
   * Returns a string representation for this actor. This implementation
   * returns the name of this actor.
   * @return a string for this actor
   */
  override def toString = "PlaylistCtrlActor"

  /**
   * Sends the current playlist to the source read actor.
   * @param startIdx the start index
   * @param initSkipPos the initial skip position
   * @param initSkipTime the initial skip time
   */
  private def sendPlaylist(startIdx: Int, initSkipPos: Long = 0,
    initSkipTime: Long = 0) {
    val pl = playlist.drop(startIdx)
    if (!pl.isEmpty) {
      sourceActor ! AddSourceStream(pl.head, startIdx, initSkipPos, initSkipTime)
      var idx = startIdx + 1
      for (uri <- pl.tail) {
        sourceActor ! AddSourceStream(uri, idx, 0, 0)
        idx += 1
      }
    }
  }

  /**
   * Reads the specified medium and constructs a playlist (either based on a
   * persistent playlist file or by using the ''PlaylistGenerator''). The new
   * playlist is sent to the source actor.
   * @param uri the URI of the medium to be read
   */
  private def handleReadMedium(uri: String) {
    val list = scanner.scan(uri)
    playlistID = store.calculatePlaylistID(list)
    val playlistData = store.loadPlaylist(playlistID)
    val settings = readPlaylistSettings()

    playlist = readPersistentPlaylist(playlistData)
    if (playlist.isEmpty) {
      constructPlaylist(list, settings)
    } else {
      setUpExistingPlaylist(playlistData.get)
    }
  }

  /**
   * Extracts the playlist from a persistent playlist file. This method reads
   * all elements referring to audio sources in the playlist. It can also deal
   * with the legacy format which contains another audio source in the section
   * for the current song.
   * @param playlistData the persistent playlist data
   * @return the extracted playlist or an empty sequence if there is none
   */
  private def readPersistentPlaylist(playlistData: Option[Elem]): Seq[String] = {
    val pl = playlistData map { readPersistentPlaylist(_) }
    pl.getOrElse(List.empty)
  }

  /**
   * Extracts the playlist from an existing persistent playlist file. Works like
   * the method with the same name, but directly operates on the existing XML
   * structures.
   * @param playlistData the XML structure representing the playlist
   * @return the extracted playlist or an empty sequence if there is none
   */
  private def readPersistentPlaylist(playlistData: Elem): Seq[String] = {
    val pl = ListBuffer.empty[String]

    val current = playlistData \ "current" \ "file" \ "@name"
    if (!current.isEmpty) {
      pl += current.text
    }

    val files = playlistData \ "list" \ "file"
    for (f <- files) {
      pl += (f \ "@name").text
    }

    pl.toList
  }

  /**
   * This method is called if a persistent playlist was found. It obtains the
   * current index and skip positions and sends the playlist to the source
   * actor.
   * @param playlistData the XML root node of the persistent playlist
   */
  private def setUpExistingPlaylist(playlistData: Elem) {
    import PlaylistCtrlActor._
    val current = playlistData \ ElemCurrent
    val skipPos = longValue(current \ ElemPosition)
    val skipTime = longValue(current \ ElemTime)
    val index = longValue(current \ ElemIndex).toInt
    sendPlaylist(index, skipPos, skipTime)
  }

  /**
   * Obtains playlist setting information. If the store does not have a settings
   * document, a default settings object is returned.
   * @return an object with playlist settings information
   */
  private def readPlaylistSettings(): PlaylistSettingsData = {
    val optSettingsData = store.loadSettings(playlistID)
    val settingsData = optSettingsData map { extractPlaylistSettings(_) }
    settingsData.getOrElse(PlaylistSettingsData(PlaylistCtrlActor.EmptyName,
      PlaylistCtrlActor.EmptyName, PlaylistCtrlActor.EmptyName,
      xml.NodeSeq.Empty))
  }

  /**
   * Extracts a data object with playlist settings from an XML representation.
   * @param elem the root XML element
   * @return the data object with playlist settings
   */
  private def extractPlaylistSettings(elem: xml.NodeSeq): PlaylistSettingsData = {
    import PlaylistCtrlActor._
    val elemOrder = elem \ ElemOrder
    PlaylistSettingsData((elem \ ElemName).text, (elem \ ElemDesc).text,
      (elemOrder \ ElemOrderMode).text, elemOrder \ ElemOrderParams)
  }

  /**
   * Constructs a new playlist based on the content of the source medium. This
   * method is called if no persistent playlist was found. In this case the
   * ''PlaylistGenerator'' is used to setup a new playlist. The newly created
   * playlist is sent to the source reader actor.
   * @param pl the sequence with the audio sources found on the medium
   * @param settings an object with playlist settings
   */
  private def constructPlaylist(pl: Seq[String], settings: PlaylistSettingsData) {
    playlist = generator.generatePlaylist(pl, settings.orderMode,
      settings.orderParams)
    sendPlaylist(0)
  }
}

/**
 * The companion object for ''PlaylistCtrlActor''.
 */
private object PlaylistCtrlActor {
  /** Constant for an empty name or description of a playlist. */
  private val EmptyName = ""

  /** Constant for the current XML element. */
  private val ElemCurrent = "current"

  /** Constant for the file XML element. */
  private val ElemFile = "file"

  /** Constant for the current index XML element. */
  private val ElemIndex = "index"

  /** Constant for the current position XML element. */
  private val ElemPosition = "position"

  /** Constant for the current time XML element. */
  private val ElemTime = "time"

  /** Constant for the settings playlist name XML element. */
  private val ElemName = "name"

  /** Constant for the settings playlist description XML element. */
  private val ElemDesc = "description"

  /** Constant for the settings playlist order XML element. */
  private val ElemOrder = "order"

  /** Constant for the order mode XML element. */
  private val ElemOrderMode = "mode"

  /** Constant for the order parameters XML element. */
  private val ElemOrderParams = "params"

  /** Constant for the file name attribute. */
  private val AttrName = "@name"

  /**
   * Returns the value of the given XML element as Long. If there is no value,
   * result is 0.
   */
  private def longValue(elem: xml.NodeSeq): Long =
    if (elem.isEmpty) 0 else elem.text.toLong
}

/**
 * A data class representing settings for a playlist. This class holds the data
 * extracted from a playlist settings XML document.
 *
 * @param name the name of the playlist
 * @param description a description of the playlist
 * @param orderMode the order mode
 * @param orderParams additional parameters for ordering the playlist
 */
private case class PlaylistSettingsData(name: String, description: String,
  orderMode: String, orderParams: xml.NodeSeq)

/**
 * A message which causes the ''PlaylistCtrlActor'' to read the medium with the
 * specified URI. A corresponding playlist will be constructed.
 * @param uri the URI of the root directory to be scanned
 */
private case class ReadMedium(uri: String)
