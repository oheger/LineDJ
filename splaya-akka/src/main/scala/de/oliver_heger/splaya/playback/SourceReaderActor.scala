package de.oliver_heger.splaya.playback

import akka.actor.{Actor, ActorRef}
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData}
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest}
import de.oliver_heger.splaya.playback.LocalBufferActor.{BufferReadActor, ReadBuffer}
import de.oliver_heger.splaya.playback.PlaybackActor.{GetAudioData, GetAudioSource}

import scala.collection.mutable

/**
 * Companion object.
 */
object SourceReaderActor {
  /**
   * An error message indicating an invalid request for an audio source. A new
   * audio source can only be requested until the current source has been fully
   * processed.
   */
  val ErrorAudioSourceInProgress =
    "Invalid request for a new AudioSource! The current source is still processed."

  /**
   * An error message indicating an invalid request for audio data. Audio data
   * can only be requested if an audio source is currently available.
   */
  val ErrorNoAudioSource = "No current AudioSource for requesting data!"

  /**
   * An error message indicating that unexpected read results were received.
   * This actor communicates with a ''FileReaderActor''. When results are
   * received that have not been requested, an error with this message is
   * generated.
   */
  val ErrorUnexpectedReadResults = "Unexpected read results received!"

  /**
   * An error message indicating that an unexpected reader actor message from
   * the buffer was received. At any time there is at most a single actor for
   * reading from the buffer. If an additional reader actor is passed via a
   * ''BufferReadActor'' message, this error is generated.
   */
  val ErrorUnexpectedBufferReadActor = "Unexpected BufferReadActor message!"

  /**
   * An error message indicating that an unexpected ''GetAudioData'' message was
   * received. Only a single request for audio data can be handled at a given
   * time.
   */
  val ErrorUnexpectedGetAudioData = "Unexpected GetAudioData message! A request for audio " +
    "data is still in progress."

  /**
   * An error message indicating an unexpected ''EndOfFile'' message. Such
   * messages should only occur when audio data is read.
   */
  val ErrorUnexpectedEndOfFile = "Unexpected EndOfFile message! There is no current request " +
    "for audio data."

  /**
   * An error message indicating an unexpected ''AudioSourceDownloadCompleted''
   * message. Before such a message can be processed, there must be a current
   * audio source.
   */
  val ErrorUnexpectedDownloadCompleted = "Unexpected AudioSourceDownloadCompleted message!"

  /**
   * A message processed by ''SourceReaderActor'' telling it that an audio
   * source has been downloaded. The message defines the final length of this
   * source. (If there is an error while downloading the source, the length may
   * be less than the original value; in this case, it has to be adapted.)
   * @param finalLength the final length of the last audio source
   */
  case class AudioSourceDownloadCompleted(finalLength: Long)
}

/**
 * An actor class which reads audio data to be played from the local buffer and
 * passes it to [[PlaybackActor]].
 *
 * This actor acts as data source for ''PlaybackActor''. On one hand, it
 * accepts messages of type ''GetAudioSource'' and ''GetAudioData''. On the
 * other hand, it interacts with [[LocalBufferActor]] to obtain reader actors
 * for the audio data stored in the buffer. Its main task is to read data
 * corresponding to single audio sources from the buffer and pass it to the
 * playback actor.
 *
 * The audio sources in the current playlist are passed to this actor via
 * messages. This happens when the local audio buffer is filled: when the data
 * of an audio source is written into the buffer, the corresponding
 * [[AudioSource]] object is sent to this actor. Thus, it can match audio
 * sources - and their associated meta data - with reader actors obtained from
 * the buffer.
 *
 * @param bufferActor the local buffer actor
 */
class SourceReaderActor(bufferActor: ActorRef) extends Actor {

  import de.oliver_heger.splaya.playback.SourceReaderActor._

  /** A queue for storing the audio sources in the current playlist. */
  private val sourceQueue = mutable.Queue.empty[AudioSource]

  /**
   * Stores the reference to an actor which requested a new audio source. This
   * field is set when currently no audio source is available. As soon as one
   * arrives, the requesting actor can be served.
   */
  private var audioSourceRequest: Option[ActorRef] = None

  /**
   * Stores the audio source which is currently processed.
   */
  private var currentSource: Option[AudioSource] = None

  /** The current file reader actor. */
  private var fileReaderActor: Option[ActorRef] = None

  /** A request for audio data which could not be served directly. */
  private var pendingDataRequest: Option[AudioDataRequest] = None

  /** A request for audio data which is currently in progress. */
  private var currentDataRequest: Option[AudioDataRequest] = None

  /** The number of bytes read in the current source. */
  private var bytesReadInCurrentSource = 0L

  /**
   * @inheritdoc This implementation directly requests a reader actor from the
   *             local buffer.
   */
  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    bufferActor ! ReadBuffer
  }

  override def receive: Receive = {
    case src: AudioSource =>
      sourceQueue += src
      audioSourceRequest foreach initCurrentAudioSource

    case readActor: BufferReadActor =>
      bufferReadActorReceived(readActor)

    case GetAudioSource =>
      currentSource match {
        case Some(_) =>
          protocolError(GetAudioSource, ErrorAudioSourceInProgress)

        case None =>
          if (sourceQueue.nonEmpty) {
            initCurrentAudioSource(sender())
          } else {
            audioSourceRequest = Some(sender())
          }
      }

    case data: GetAudioData =>
      currentSource match {
        case Some(source) =>
          currentSource = dataRequestReceived(sender(), source, data)

        case None =>
          protocolError(data, ErrorNoAudioSource)
      }

    case readData: ArraySource =>
      if (readResultReceived(readData)) {
        bytesReadInCurrentSource += readData.length
      }

    case eof: EndOfFile =>
      fileReaderEndOfFile(eof)

    case compl: AudioSourceDownloadCompleted =>
      handleSourceDownloadCompleted(compl)

    case CloseRequest =>
      fileReaderActor foreach context.stop
      sender ! CloseAck(self)
      context become closing
  }

  /**
   * A specialized ''Receive'' function that handles messages after a closing
   * request. This function ensures that all file reader actors received by
   * this actor are stopped. This is necessary to deal with a race condition:
   * The close request may occur before an answer from the buffer actor. In
   * this case, the reader actor would not be stopped, and the buffer actor
   * could not finish its closing sequence.
   */
  def closing: Receive = {
    case BufferReadActor(actor) =>
      context stop actor
  }

  /**
   * Sends a message about a protocol violation to the current sender.
   * @param msg the unexpected message
   * @param errorText the error text
   */
  private def protocolError(msg: Any, errorText: String): Unit = {
    sender ! PlaybackProtocolViolation(msg, errorText)
  }

  /**
   * Initializes the current audio source from the queue and notifies the
   * requesting actor.
   * @param client the actor requesting the audio source
   * @return the new current audio source
   */
  private def initCurrentAudioSource(client: ActorRef): AudioSource = {
    assert(sourceQueue.nonEmpty, "No sources available")
    val src = sourceQueue.dequeue()
    client ! src
    currentSource = Some(src)
    bytesReadInCurrentSource = 0
    src
  }

  /**
   * Notifies this object that a file reader actor from the local buffer was
   * received. This actor is stored internally; incoming requests for audio
   * data are delegated to it.
   * @param msg the message regarding the file reader actor
   */
  private def bufferReadActorReceived(msg: BufferReadActor): Unit = {
    fileReaderActor match {
      case Some(_) =>
        protocolError(msg, ErrorUnexpectedBufferReadActor)

      case None =>
        fileReaderActor = Some(msg.readerActor)
        serveDataRequestIfPossible()
    }
  }

  /**
   * A request for audio data was received. It is either handled directly or
   * put on hold until a file reader actor is available.
   * @param client the actor who sent this request
   * @param source the current audio source
   * @param msg the message containing the request
   * @return the next current audio source; this may be ''None'' when the end was reached
   */
  private def dataRequestReceived(client: ActorRef, source: AudioSource, msg: GetAudioData):
  Option[AudioSource] = {
    if (pendingDataRequest.isDefined) {
      protocolError(msg, ErrorUnexpectedGetAudioData)
      Some(source)

    } else {
      val remainingSize = source.length - bytesReadInCurrentSource
      if (remainingSize > 0) {
        pendingDataRequest = Some(AudioDataRequest(client, ReadData(math.min(msg.length,
          remainingSize.toInt))))
        serveDataRequestIfPossible()
        Some(source)
      } else {
        client ! EndOfFile(null)
        None
      }
    }
  }

  /**
   * The result of a read operation was received. This method ensures that it
   * gets forwarded to the original sender.
   * @param result the read result
   * @return a flag whether this was a valid read result
   */
  private def readResultReceived(result: ArraySource): Boolean = {
    currentDataRequest match {
      case Some(req) =>
        req.sender ! result
        currentDataRequest = None
        true

      case None =>
        protocolError(result, ErrorUnexpectedReadResults)
        false
    }
  }

  /**
   * An ''EndOfFile'' was received from the current file reader actor. In this
   * case, a new reader actor has to be requested.
   * @param eof the end of file message
   */
  private def fileReaderEndOfFile(eof: EndOfFile): Unit = {
    if (currentDataRequest.isEmpty) {
      protocolError(eof, ErrorUnexpectedEndOfFile)

    } else {
      fetchAndResetCurrentFileReaderActor() foreach { act =>
        context stop act
        bufferActor ! ReadBuffer
      }
    }
  }

  /**
   * Checks whether a request for audio data can now be handled. If so, it is
   * passed to the file reader actor.
   */
  private def serveDataRequestIfPossible(): Unit = {
    for {
      actor <- fileReaderActor
      request <- fetchAndResetPendingDataRequest()} {
      actor ! request.request
    }
  }

  /**
   * Returns the optional pending request for audio data. If it is defined,
   * it is reset.
   * @return the pending audio data request
   */
  private def fetchAndResetPendingDataRequest(): Option[AudioDataRequest] = {
    currentDataRequest = pendingDataRequest
    pendingDataRequest = None
    currentDataRequest
  }

  /**
   * Returns the optional current file reader actor. If it is defined, it is reset.
   * @return the current file reader actor
   */
  private def fetchAndResetCurrentFileReaderActor(): Option[ActorRef] = {
    val result = fileReaderActor
    fileReaderActor = None
    result
  }

  /**
   * Handles a message about a completed download of an audio source.
   * @param compl the completion message
   */
  private def handleSourceDownloadCompleted(compl: AudioSourceDownloadCompleted): Unit = {
    if (!adaptSourceLengthInQueue(compl)) {
      if (currentSource.isDefined) {
        currentSource = currentSource map (_.copy(length = compl.finalLength))
      } else {
        protocolError(compl, ErrorUnexpectedDownloadCompleted)
      }
    }
  }

  /**
   * Adapts the length of an audio source (whose download was completed) in the
   * queue of sources if necessary.
   * @param compl the download completed message
   * @return a flag whether the affected audio source was part of the queue
   */
  private def adaptSourceLengthInQueue(compl: AudioSourceDownloadCompleted): Boolean = {
    if (sourceQueue.nonEmpty) {
      val lastSource = sourceQueue(sourceQueue.length - 1)
      if (lastSource.length != compl.finalLength) {
        sourceQueue dequeueFirst (_ == lastSource)
        sourceQueue += lastSource.copy(length = compl.finalLength)
      }
      true
    }
    else false
  }
}

/**
 * A data class representing a request for audio data.
 * @param sender the sender of the request
 * @param request the actual request
 */
private case class AudioDataRequest(sender: ActorRef, request: ReadData)
