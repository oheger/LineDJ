package de.oliver_heger.splaya.playlist.impl

import scala.actors.Actor
import de.oliver_heger.splaya.engine.msg.Exit
import de.oliver_heger.splaya.PlaylistData
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.PlaylistUpdate
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.engine.msg.AccessSourceMedium
import de.oliver_heger.splaya.PlayerShutdown
import java.io.Closeable

/**
 * An actor implementation which is responsible for obtaining meta data for all
 * audio sources in the current playlist.
 *
 * This actor listens for messages indicating that a new playlist was
 * constructed. In such a case, the list with all audio sources is obtained and
 * processed, starting with the current index. For each audio source an
 * [[de.oliver_heger.splaya.playlist.impl.ExtractSourceDataRequest]] message is
 * sent to the ''AudioSourceDataExtractorActor''. As soon as meta data about an
 * audio source becomes available, a [[de.oliver_heger.splaya.PlaylistUpdate]]
 * event is sent out.
 *
 * This actor also takes into account whether the source medium is currently
 * accessed by the ''SourceReaderActor''. If this is the case, loading audio
 * meta data is interrupted.
 *
 * @param gateway the gateway object
 * @param sourceDataExtractor the actor for extracting audio source data
 */
class PlaylistDataExtractorActor(gateway: Gateway, sourceDataExtractor: Actor)
  extends Actor {
  /** The playlist to be processed. */
  private var playlistData: PlaylistDataImpl = _

  /** The current playlist ID. */
  private var playlistID: Long = 0

  /** The number of playlist items which have to be processed. */
  private var itemsToProcess = 0

  /** The index of the current playlist item. */
  private var index = 0

  /** A flag whether the source medium is locked. */
  private var mediumLocked = false

  /** A flag whether a request is currently pending. */
  private var requestPending = false

  /**
   * The main message loop of this actor.
   */
  def act() {
    var running = true

    while (running) {
      receive {
        case cl: Closeable =>
          cl.close()
          running = false

        case PlayerShutdown =>
          sourceDataExtractor ! Exit
          running = false

        case pld: PlaylistDataImpl =>
          handleNewPlaylist(pld)

        case res: ExtractSourceDataResult =>
          handleExtractResult(res)

        case AccessSourceMedium(locked) =>
          handleAccessMedium(locked)

        case _ => // ignore other messages
      }
    }
  }

  /**
   * Returns a string representation for this object. This implementation
   * returns the name of this actor.
   * @return a string for this object
   */
  override def toString = "PlaylistDataExtractorActor"

  /**
   * Handles a new playlist message.
   * @param pld the ''PlaylistData'' received as message
   */
  private def handleNewPlaylist(pld: PlaylistDataImpl) {
    playlistID += 1
    playlistData = pld
    index = pld.startIndex
    requestPending = false
    itemsToProcess = pld.size
    processPlaylistItem()
  }

  /**
   * Handles a message that a new extraction result was received.
   * @param res the result
   */
  private def handleExtractResult(res: ExtractSourceDataResult) {
    if (playlistID == res.playlistID) {
      if (!res.data.isEmpty) {
        playlistData.setAudioSourceData(res.index, res.data.get)
        gateway.publish(createPlaylistUpdateMessage(res))
      }

      requestPending = false
      processPlaylistItem()
    }
  }

  /**
   * Handles a message that the source medium is accessed.
   * @param locked a flag whether the medium is currently locked
   */
  private def handleAccessMedium(locked: Boolean) {
    mediumLocked = locked
    if (!mediumLocked) {
      processPlaylistItem()
    }
  }

  /**
   * Processes another item in the playlist if this is currently possible. This
   * method checks whether there are still remaining items. If so, and if the
   * source medium is not locked and no request is pending, another request is
   * sent to the extractor actor.
   */
  private def processPlaylistItem() {
    if (canProcessItem) {
      sourceDataExtractor ! createExtractRequest()
      itemsToProcess -= 1
      incrementIndex()
      requestPending = true
    }
  }

  /**
   * Checks whether a new request for a playlist item can be sent.
   * @return '''true''' if a playlist item can be processed, '''false'''
   * otherwise
   */
  private def canProcessItem =
    itemsToProcess > 0 && !mediumLocked && !requestPending

  /**
   * Increments the index of the current playlist item performing a wrap if
   * necessary.
   */
  private def incrementIndex() {
    index += 1
    if (index >= playlistData.size) {
      index = 0
    }
  }

  /**
   * Creates a request for extracting audio source data for the current item in
   * the playlist.
   * @return the request
   */
  private def createExtractRequest() =
    ExtractSourceDataRequest(playlistID = playlistID, index = index,
      uri = playlistData.getURI(index),
      mediumURI = playlistData.settings.mediumURI, sender = this)

  /**
   * Creates a message indicating that the playlist was updated. Messages of
   * this type are sent out when meta data about a playlist item becomes
   * available.
   * @param res the result of an extraction
   * @return the playlist update message
   */
  private def createPlaylistUpdateMessage(res: ExtractSourceDataResult) =
    PlaylistUpdate(playlistData, res.index)
}
