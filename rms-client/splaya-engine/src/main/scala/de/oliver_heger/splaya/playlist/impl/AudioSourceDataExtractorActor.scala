package de.oliver_heger.splaya.playlist.impl

import scala.actors.Actor
import de.oliver_heger.splaya.playlist.AudioSourceDataExtractor
import de.oliver_heger.splaya.AudioSourceData
import de.oliver_heger.splaya.engine.msg.Exit

/**
 * An actor implementation which extracts meta data from audio sources.
 *
 * This actor reacts on messages specifying a single audio source. It then uses
 * a [[de.oliver_heger.splaya.playlist.AudioSourceDataExtractor]] implementation
 * to obtain meta data about this audio source. The information extracted is
 * then sent back to the sender.
 *
 * @param extractor the ''AudioSourceDataExtractor'' to be used
 */
class AudioSourceDataExtractorActor(extractor: AudioSourceDataExtractor)
  extends Actor {
  /**
   * The main method of this actor. The actor mainly reacts on messages
   * requesting meta data for audio sources. Such requests are delegated to the
   * ''AudioSourceDataExtractor'' used by this object. Results are sent back to
   * the requesting actor.
   */
  def act() {
    var running = true

    while (running) {
      receive {
        case ex: Exit =>
          ex.confirmed(this)
          running = false

        case req: ExtractSourceDataRequest =>
          handleRequest(req)
      }
    }
  }

  /**
   * Returns a string representation for this actor. This string contains the
   * name of this actor.
   * @return a string for this actor
   */
  override def toString = "AudioSourceDataExtractorActor"

  /**
   * Obtains meta data for an audio source based on the given request.
   * @param req the request for audio source data
   */
  private def handleRequest(req: ExtractSourceDataRequest) {
    val data = extractor.extractAudioSourceData(req.uri)
    req.sender ! ExtractSourceDataResult(req.playlistID, req.index, data)
  }
}

/**
 * A message which can be sent to the ''AudioSourceDataExtractorActor'' to
 * request audio data for a specific audio source. The audio source in question
 * is identified by its URI. The result of the extraction is sent to the
 * specified actor in form of an ''ExtractSourceDataResult'' message.
 * @param playlistID a unique ID for the current playlist; this value is used
 * to deal with multiple playlists (for instance, a new playlist can be set
 * while the former one is still processed)
 * @param uri the URI of the audio source to be processed
 * @param index the index of the audio source affected by this operation in the
 * playlist
 * @param sender the actor to which to sent the result
 */
case class ExtractSourceDataRequest(playlistID: Long, uri: String, index: Int,
  sender: Actor)

/**
 * A message providing the result of a request for extracting audio source data.
 * A message of this type is sent by the
 * [[de.oliver_heger.splaya.playlist.impl.AudioSourceDataExtractorActor]] as
 * answer for an ''ExtractSourceDataRequest'' message. This answer is sent in
 * any case, even if data extraction failed.
 * @param playlistID the ID of the affected playlist
 * @param index the index of the affected audio source in the playlist
 * @param data an ''Option'' with the meta data extracted for the source
 */
case class ExtractSourceDataResult(playlistID: Long, index: Int,
  data: Option[AudioSourceData])
