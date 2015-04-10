package de.oliver_heger.splaya.playback

import akka.actor.{Actor, ActorRef}
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest}
import de.oliver_heger.splaya.playback.LocalBufferActor.{BufferFilled, FillBuffer}

import scala.collection.mutable

/**
 * Companion object.
 */
object SourceDownloadActor {
  /**
   * Constant for an error message caused by an unexpected download response
   * message. Responses are only accepted after a request was sent out.
   */
  val ErrorUnexpectedDownloadResponse = "Unexpected AudioSourceDownloadResponse message!"

  /**
   * Constant for an error message caused by an unexpected buffer filled
   * message. Such messages are only accepted after a reader actor has been
   * passed to the buffer actor.
   */
  val ErrorUnexpectedBufferFilled = "Unexpected BufferFilled message!"
}

/**
 * An actor which downloads audio sources from a source actor into the local
 * buffer.
 *
 * This actor accepts messages which define a playlist. During playback, it
 * requests the sources in the playlist from a source actor. This is done in
 * form of actor references which actually point to file reader actors. These
 * references are passed to the [[LocalBufferActor]] actor which reads the
 * data and stores it locally. (Note that these actors have to be stopped after
 * they have been read completely!)
 *
 * A source object which has been fully stored in the local buffer is also
 * reported to an outbound actor. This actor is then able to read data from the
 * local buffer and manage the actual audio playback.
 *
 * @param srcActor the actor from which audio sources are requested
 * @param bufferActor the local buffer actor
 * @param readerActor the actor which reads audio data from the buffer
 */
class SourceDownloadActor(srcActor: ActorRef, bufferActor: ActorRef, readerActor: ActorRef)
  extends Actor {

  import de.oliver_heger.splaya.playback.SourceDownloadActor._

  /** A queue for the items in the playlist. */
  private val playlist = mutable.Queue.empty[AudioSourcePlaylistInfo]

  /** The playlist item which is currently downloaded. */
  private var currentDownload: Option[AudioSourcePlaylistInfo] = None

  /** A download response which is about to be processed. */
  private var downloadToProcess: Option[AudioSourceDownloadResponse] = None

  /** The read actor currently processed by the buffer. */
  private var currentReadActor: Option[ActorRef] = None

  override def receive: Receive = {
    case src: AudioSourcePlaylistInfo =>
      playlist += src
      downloadIfPossible()

    case response: AudioSourceDownloadResponse =>
      resetCurrentDownload() match {
        case Some(info) =>
          if (isValidDownloadResponse(response)) {
            readerActor ! AudioSource(uri = info.sourceID.uri, index = info.index, length =
              response.length, skip = info.skip, skipTime = info.skipTime)
            downloadToProcess = fillBufferIfPossible(response)
          }
          downloadIfPossible()

        case None =>
          sender ! PlaybackProtocolViolation(response, ErrorUnexpectedDownloadResponse)
      }

    case filled: BufferFilled =>
      currentReadActor match {
        case Some(actor) =>
          readerActor ! SourceReaderActor.AudioSourceDownloadCompleted(filled.sourceLength)
          resetDownloadToProcess() foreach fillBufferIfPossible
          downloadIfPossible()
          context stop actor

        case None =>
          sender ! PlaybackProtocolViolation(filled, ErrorUnexpectedBufferFilled)
      }

    case CloseRequest =>
      currentReadActor foreach context.stop
      sender ! CloseAck(self)
  }

  /**
   * Checks whether the specified ''AudioSourceDownloadResponse'' object
   * indicates a valid download. The media manager actor returns responses with
   * a negative length to indicate that a requested source could not be
   * delivered.
   * @param response the response object to be checked
   * @return a flag whether the response is valid
   */
  private def isValidDownloadResponse(response: AudioSourceDownloadResponse): Boolean =
    response.length >= 0

  /**
   * Sets the current download to ''None''. The old value is returned.
   * @return the current download before it was reset
   */
  private def resetCurrentDownload(): Option[AudioSourcePlaylistInfo] = {
    val result = currentDownload
    currentDownload = None
    result
  }

  /**
   * Initiates the download of an audio source if this is currently possible.
   */
  private def downloadIfPossible(): Unit = {
    if (playlist.nonEmpty && downloadToProcess.isEmpty && currentDownload.isEmpty) {
      val info = playlist.dequeue()
      srcActor ! info.sourceID
      currentDownload = Some(info)
    }
  }

  /**
   * Sets the download to be processed to ''None'' and returns the old value.
   * @return the download to be processed before it was reset
   */
  private def resetDownloadToProcess(): Option[AudioSourceDownloadResponse] = {
    val result = downloadToProcess
    downloadToProcess = None
    currentReadActor = None
    result
  }

  /**
   * Triggers a new fill operation when a response for a download request is
   * received. If possible, the new content is directly filled into the buffer;
   * otherwise, it has to be parked until the buffer can accept further input.
   * @param response the response
   * @return the new value for the response to be processed
   */
  private def fillBufferIfPossible(response: AudioSourceDownloadResponse):
  Option[AudioSourceDownloadResponse] = {
    currentReadActor match {
      case Some(_) =>
        Some(response)

      case None =>
        bufferActor ! FillBuffer(response.contentReader)
        currentReadActor = Some(response.contentReader)
        None
    }
  }
}
