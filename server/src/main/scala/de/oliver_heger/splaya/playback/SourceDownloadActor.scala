package de.oliver_heger.splaya.playback

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Cancellable}
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest}
import de.oliver_heger.splaya.media.MediaManagerActor
import de.oliver_heger.splaya.playback.LocalBufferActor.{BufferFilled, FillBuffer}
import de.oliver_heger.splaya.utils.SchedulerSupport

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * Companion object.
 */
object SourceDownloadActor {

  /**
   * A message processed by ''SourceDownloadActor'' telling it to send a
   * message to the source actor that the current download operation is still
   * in progress. Such messages have to be sent periodically to indicate that
   * this client is still alive.
   */
  case object ReportReaderActorAlive

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

  /** The prefix for configuration properties. */
  private val ConfigurationPrefix = "splaya.playback."

  /** The property for initial delay for download in progress messages. */
  private val PropReaderAliveDelay = ConfigurationPrefix + "downloadProgressMessageDelay"

  /** The property for the interval of download in progress messages. */
  private val PropReaderAliveInterval = ConfigurationPrefix + "downloadProgressMessageInterval"
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
  me: SchedulerSupport =>

  import de.oliver_heger.splaya.playback.SourceDownloadActor._

  /** Initial delay for download in progress messages. */
  private val readerAliveDelay = durationProperty(PropReaderAliveDelay)

  /** Interval for download in progress messages. */
  private val readerAliveInterval = durationProperty(PropReaderAliveInterval)

  /** A queue for the items in the playlist. */
  private val playlist = mutable.Queue.empty[AudioSourcePlaylistInfo]

  /** The playlist item which is currently downloaded. */
  private var currentDownload: Option[AudioSourcePlaylistInfo] = None

  /** A download response which is about to be processed. */
  private var downloadToProcess: Option[AudioSourceDownloadResponse] = None

  /** The read actor currently processed by the buffer. */
  private var currentReadActor: Option[ActorRef] = None

  /** Cancellable for periodic download in progress notifications. */
  private var cancellableReaderAlive: Option[Cancellable] = None

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    import context.dispatcher
    super.preStart()
    cancellableReaderAlive = Some(scheduleMessage(readerAliveDelay, readerAliveInterval, self,
      ReportReaderActorAlive))
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    cancellableReaderAlive foreach (_.cancel())
    super.postStop()
  }

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

    case ReportReaderActorAlive =>
      currentReadActor foreach (srcActor ! MediaManagerActor.ReaderActorAlive(_))
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

  /**
   * Resolves a configuration property of type duration.
   * @param key the property key
   * @return the value of this property
   */
  private def durationProperty(key: String): FiniteDuration = {
    val millis = context.system.settings.config.getDuration(key, TimeUnit.MILLISECONDS)
    FiniteDuration(millis, MILLISECONDS)
  }
}
