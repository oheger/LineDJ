package de.oliver_heger.mediastore.storelistener

import java.io.Closeable
import java.math.BigInteger
import java.util.EventListener

import scala.actors.Actor

import org.slf4j.LoggerFactory

import de.oliver_heger.mediastore.localstore.MediaStore
import de.oliver_heger.mediastore.service.ObjectFactory
import de.oliver_heger.mediastore.service.SongData
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.PlaybackSourceEnd
import de.oliver_heger.splaya.PlayerShutdown
import de.oliver_heger.splaya.PlaylistData
import de.oliver_heger.splaya.PlaylistUpdate

/**
 * An actor which is responsible for storing information about audio sources
 * played by the audio engine in the local database.
 *
 * This actor is registered as listener at the audio player engine. Whenever
 * an event arrives indicating that an audio source has been fully played,
 * and the meta information for this source are available, this data is written
 * into the local media store. That way a record about the songs played is
 * created.
 *
 * Implementation of this actor is complicated by the fact that the extraction
 * of audio information runs in a background task, independent from the audio
 * player. Thus, a source might already have been played before its meta
 * information is retrieved. So this constellation - although unlikely - has to
 * be taken into account.
 *
 * @param store a reference to the ''MediaStore'' service
 */
class StoreListenerActor(val store: MediaStore) extends Actor
  with EventListener {
  /** Constant for the milliseconds factor. */
  private val Millis = 1000

  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[StoreListenerActor])

  /** The factory for creating ''SongData'' objects- */
  private val factory = new ObjectFactory

  /** An array keeping track about the songs which have been played. */
  private var playedSources: Array[Int] = _

  /** An array keeping track about available media information. */
  private var mediaDataAvailable: Array[Boolean] = _

  /** The current playlist. */
  private var playlist: Option[PlaylistData] = None

  def act() {
    var running = true

    while (running) {
      receive {
        case cl: Closeable =>
          cl.close()
          running = false

        case PlayerShutdown =>
          running = false

        case pl: PlaylistData =>
          initPlaylist(pl)

        case PlaylistUpdate(_, idx) =>
          if (playlist.isDefined) {
            mediaDataAvailable(idx) = true
            storeAudioDataIfRequired(idx)
          }

        case PlaybackSourceEnd(src, false) =>
          if (playlist.isDefined) {
            playedSources(src.index) += 1
            storeAudioDataIfRequired(src.index)
          }

        case _ => // ignore other messages
      }
    }

    log.info("StoreListenerActor exits.")
  }

  /**
   * Creates a ''SongData'' object for the specified playlist item.
   * @param srcData the object with meta information about the playlist item
   * @return a ''SongData'' object for this item
   */
  protected[storelistener] def createSongData(srcData: AudioSourceData): SongData = {
    def convertNumber(value: Long): BigInteger =
      if (value == 0) null
      else BigInteger.valueOf(value)

    val data = factory.createSongData()

    data.setAlbumName(srcData.albumName)
    data.setArtistName(srcData.artistName)
    data.setName(srcData.title)
    data.setDuration(convertNumber(srcData.duration / Millis))
    data.setInceptionYear(convertNumber(srcData.inceptionYear))
    data.setTrackNo(convertNumber(srcData.trackNo))
    data
  }

  /**
   * A new playlist was created. This causes the internal arrays to be
   * re-initialized.
   */
  private def initPlaylist(pl: PlaylistData) {
    playlist = Some(pl)
    playedSources = new Array(pl.size)
    mediaDataAvailable = new Array(pl.size)
  }

  /**
   * Updates the local media store with information about the specified playlist
   * item if this information is available. This method is called whenever an
   * audio source was played or audio data was retrieved. If all conditions are
   * fulfilled, a ''SongData'' object is created for the playlist item, and the
   * media store service is called.
   * @param idx the index of the affected playlist item
   */
  private def storeAudioDataIfRequired(idx: Int) {
    if (playedSources(idx) > 0 && mediaDataAvailable(idx)) {
      log.info("Storing audio information for playlist item {}.", idx)
      val data = createSongData(playlist.get.getAudioSourceData(idx))
      store.updateSongData(data, playedSources(idx))
      playedSources(idx) = 0
    }
  }
}
