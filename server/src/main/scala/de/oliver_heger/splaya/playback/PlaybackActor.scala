package de.oliver_heger.splaya.playback

import akka.actor.{Props, Actor, ActorLogging, ActorRef}
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest, DynamicInputStream}
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadResult}
import de.oliver_heger.splaya.playback.LineWriterActor.{AudioDataWritten, WriteAudioData}
import de.oliver_heger.splaya.utils.ChildActorFactory

/**
 * Companion object of ''PlaybackActor''.
 */
object PlaybackActor {

  /**
   * A message sent by ''PlaybackActor'' to request new audio data.
   *
   * Messages of this type are sent by the playback actor to its source actor
   * when it can handle additional audio data to be played. The length is an
   * indicator for the amount of data it can handle currently; but it is up to
   * the receiver to ignore it.
   * @param length a hint for the amount of data that is desired
   */
  case class GetAudioData(length: Int)

  /**
   * A message sent by ''PlaybackActor'' to request a new audio source.
   * This message is sent by the actor when started initially and when
   * an audio file has been played completely.
   */
  case object GetAudioSource

  /**
   * A message received by ''PlaybackActor'' telling it to add a new sub
   * ''PlaybackContextFactory''. By sending messages of this type the
   * context factories available can be initialized.
   * @param factory the factory to be added
   */
  case class AddPlaybackContextFactory(factory: PlaybackContextFactory)

  /**
   * A message received by ''PlaybackActor'' telling it to start or resume
   * playback. This message enables playback. If all criteria are fulfilled
   * (e.g. sufficient data is available, a playback context can be created),
   * the next steps for playing audio are performed.
   */
  case object StartPlayback

  /**
   * A message received by ''PlaybackActor'' telling it to stop current
   * playback. As long as playback is disabled, no data is sent to the line
   * writer actor. However, data is still loaded from the source until the
   * buffer is filled.
   */
  case object StopPlayback

  /**
   * A message received by ''PlaybackActor'' telling it to skip the current
   * audio source. When this message is received further audio data is
   * requested from the source actor until the end of the current audio file
   * is reached, but this data is no longer sent to the line writer actor.
   */
  case object SkipSource

  /** The prefix for all configuration properties related to this actor. */
  private val PropertyPrefix = "splaya.playback."

  /** Configuration property for the maximum audio buffer size. */
  private val PropAudioBufferSize = PropertyPrefix + "audioBufferSize"

  /**
   * Configuration property for the minimum number of bytes in the audio buffer before a
   * playback context can be created. The creation of a playback context may read
   * data from the current audio buffer. Therefore, it has to be ensured that
   * the buffer is filled to a certain degree. This property controls the number
   * of bytes which must be available in the buffer.
   */
  private val PropContextLimit = PropertyPrefix + "playbackContextLimit"

  private class PlaybackActorImpl(dataSource: ActorRef) extends PlaybackActor(dataSource) with
  ChildActorFactory

  /**
   * Creates a ''Props'' object for creating an instance of this actor class.
   * @param dataSource the actor which provides the data to be played
   * @return a ''Props'' object for creating an instance
   */
  def apply(dataSource: ActorRef): Props = Props(classOf[PlaybackActorImpl], dataSource)
}

/**
 * An actor which is responsible for the playback of audio sources.
 *
 * Audio sources to be played are represented by [[AudioSource]] objects.
 * Messages of this type are processed directly; however, it is only possible
 * to play a single audio source at a given time.
 *
 * With an audio source in place, this actor performs a set of tasks: Its main
 * responsibility is managing an input stream with the audio data of the
 * current source. This is a dynamic input stream that is filled from streamed
 * audio data; this actor keeps requesting new chunks of audio data until the
 * stream is filled up to a configurable amount of data. If this amount is
 * reached, a [[PlaybackContext]] can be created, and playback can start.
 *
 * During playback, chunks of bytes are read from the stream of the
 * ''PlaybackContext'' and sent to a [[LineWriterActor]]. This is done until
 * the current audio source is exhausted. It is also possible to pause playback
 * and continue it at any time. For this purpose, start and stop messages are
 * processed.
 *
 * For more information about the protocol supported by this actor refer to the
 * description of the message objects defined by the companion object.
 *
 * @param dataSource the actor which provides the data to be played
 */
class PlaybackActor(dataSource: ActorRef) extends Actor with ActorLogging {

  this: ChildActorFactory =>

  import de.oliver_heger.splaya.playback.PlaybackActor._

  /** The size of the in-memory audio buffer hold by this class. */
  private val audioBufferSize = context.system.settings.config.getInt(PropAudioBufferSize)

  /** The number of bytes in the buffer before a playback context can be created. */
  private val playbackContextLimit = context.system.settings.config.getInt(PropContextLimit)

  /** The current playback context factory. */
  private var contextFactory = new CombinedPlaybackContextFactory(Nil)

  /** The line writer actor. */
  private var lineWriterActor: ActorRef = _

  /** The current audio source. */
  private var currentSource: Option[AudioSource] = None

  /** The stream which stores the currently available audio data. */
  private val audioDataStream = new DynamicInputStream

  /** The current playback context. */
  private var playbackContext: Option[PlaybackContext] = None

  /** An array for playing audio data chunk-wise. */
  private var audioChunk: Array[Byte] = _

  /** An actor which triggered a close request. */
  private var closingActor: ActorRef = _

  /** The skip position of the current source. */
  private var skipPosition = 0L

  /** The number of bytes processed from the current audio source so far. */
  private var bytesProcessed = 0L

  /** A flag whether a request for audio data is pending. */
  private var audioDataPending = false

  /** A flag whether a request for playing audio data is pending. */
  private var audioPlaybackPending = false

  /** A flag whether playback is currently enabled. */
  private var playbackEnabled = false

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    lineWriterActor = createChildActor(Props[LineWriterActor])
  }

  override def receive: Receive = {
    case src: AudioSource =>
      if (currentSource.isEmpty) {
        currentSource = Some(src)
        audioDataPending = false
        skipPosition = src.skip
        bytesProcessed = 0
        requestAudioDataIfPossible()
      } else {
        sender ! PlaybackProtocolViolation(src, "AudioSource is already processed!")
      }

    case data: ArraySource =>
      if (checkAudioDataResponse(data)) {
        handleNewAudioData(data)
        playback()
      }

    case eof: EndOfFile =>
      if (checkAudioDataResponse(eof)) {
        audioDataStream.complete()
        assert(currentSource.isDefined)
        if (skipPosition > currentSource.get.length) {
          sourceCompleted()
        }
        playback()
      }

    case AudioDataWritten =>
      if (!audioPlaybackPending) {
        sender ! PlaybackProtocolViolation(AudioDataWritten, "Unexpected AudioDataWritten message" +
          " received!")
      } else {
        audioPlaybackPending = false
        playback()
      }

    case AddPlaybackContextFactory(factory) =>
      contextFactory = contextFactory addSubFactory factory

    case StartPlayback =>
      playbackEnabled = true
      playback()

    case StopPlayback =>
      stopPlayback()

    case SkipSource =>
      enterSkipMode()

    case CloseRequest =>
      handleCloseRequest()
  }

  /**
   * Returns a flag whether playback is currently enabled.
   * @return a flag whether playback is enabled
   */
  def isPlaying = playbackEnabled

  /**
   * An alternative ''Receive'' function which is installed when the actor is
   * to be closed, but there is still a playback operation in progress. In this
   * case, we have to wait until the playback of the current chunk is finished.
   * Then the close operation can be actually performed.
   */
  private def closing: Receive = {
    case AudioDataWritten =>
      closeActor()

    case msg =>
      sender ! PlaybackProtocolViolation(msg, "Actor is closing!")
  }

  /**
   * Checks whether a received message regarding new audio data is valid in the
   * current state. If this is not the case, a protocol error message is sent.
   * @return a flag whether the message is valid and can be handled
   */
  private def checkAudioDataResponse(msg: Any): Boolean = {
    if (!audioDataPending) {
      sender ! PlaybackProtocolViolation(msg, "Received unexpected data!")
      false
    } else {
      audioDataPending = false
      true
    }
  }

  /**
   * Handles new audio data which has been sent to this actor. The
   * data has to be appended to the audio buffer - if this is allowed by the
   * current skip position.
   * @param data the data source to be added
   */
  private def handleNewAudioData(data: ArraySource): Unit = {
    val skipSource = ArraySourceImpl(data, (skipPosition - bytesProcessed).toInt)
    if (skipSource.length > 0) {
      audioDataStream append skipSource
    }
    bytesProcessed += data.length
  }

  /**
   * Executes all currently possible steps for playing audio data. This method
   * is called whenever the state of this actor changes. Based on the current
   * state, it is checked what actions should and can be performed (e.g.
   * requesting further audio data, feeding the line writer actor, etc.). This
   * typically triggers one or more messages to be sent to collaborator actors.
   */
  private def playback(): Unit = {
    requestAudioDataIfPossible()
    playbackAudioDataIfPossible()
  }

  /**
   * Stops playback. An internal flag is reset indicating that no audio data
   * must be played.
   */
  private def stopPlayback(): Unit = {
    playbackEnabled = false
  }

  /**
   * Sends a request for new audio data to the source actor if this is
   * currently allowed.
   */
  private def requestAudioDataIfPossible(): Unit = {
    if (!audioDataPending) {
      currentSource match {
        case None =>
          dataSource ! GetAudioSource
          audioDataPending = true
        case Some(_) =>
          requestAudioDataFromSourceIfPossible()
      }
    }
  }

  /**
   * Sends a request for new audio data for the current audio source to the
   * source actor if this is currently allowed.
   */
  def requestAudioDataFromSourceIfPossible(): Unit = {
    if (!audioDataStream.completed) {
      val remainingCapacity = audioBufferSize - bytesInAudioBuffer
      if (remainingCapacity > 0) {
        dataSource ! GetAudioData(remainingCapacity)
        audioDataPending = true
      }
    }
  }

  /**
   * Communicates with the line writer actor in order to play audio data.
   * Depending on the current state (bytes available in the audio buffer,
   * playback enabled, etc.) messages to the line writer actor are sent.
   */
  private def playbackAudioDataIfPossible(): Unit = {
    if (!audioPlaybackPending) {
      fetchPlaybackContext() foreach { ctx =>
        if (isPlaying) {
          if (audioBufferFilled) {
            val len = ctx.stream.read(audioChunk)
            if (checkSourceEnd(len)) {
              lineWriterActor ! WriteAudioData(ctx.line, ReadResult(audioChunk, len))
              audioPlaybackPending = true
            }
            requestAudioDataIfPossible()
          }
        }
      }
    }
  }

  /**
   * Checks whether the audio buffer is filled sufficiently to extract audio
   * data. This method tests the current amount of audio data available against
   * the ''playbackContextLimit'' configuration property. However, if the end
   * of the audio source has already been reached, the limit can be ignored.
   * @return a flag whether the audio buffer is filled sufficiently
   */
  private def audioBufferFilled: Boolean = {
    bytesInAudioBuffer >= playbackContextLimit || audioDataStream.completed
  }

  /**
   * Checks whether the current audio source has been completely played. The
   * return value is '''true''' if further data for playback is available.
   * If the end of the audio source is reached, it is closed; then playback of
   * the next source can start.
   * @param bytesRead the number of bytes read from the audio buffer
   * @return a flag whether data for playback is available
   */
  private def checkSourceEnd(bytesRead: Int): Boolean = {
    if (bytesRead < audioChunk.length) {
      sourceCompleted()
    }
    bytesRead > 0
  }

  /**
   * Sets internal flags that cause the current source to be skipped.
   */
  private def enterSkipMode(): Unit = {
    skipPosition = currentSource.get.length + 1
    audioDataStream.clear()
    requestAudioDataIfPossible()
  }

  /**
   * Marks the current source as completely processed. Playback will continue
   * with the next audio source in the playlist.
   */
  private def sourceCompleted(): Unit = {
    log.info("Finished playback of audio source {}.", currentSource.get)
    audioDataStream.clear()
    currentSource = None
    closePlaybackContext()
  }

  /**
   * Tries to obtain the current playback context if possible. If a context
   * already exists for the current source, it is directly returned. Otherwise,
   * a new one is created if and only if all preconditions are met.
   * @return an option for the current playback context
   */
  private def fetchPlaybackContext(): Option[PlaybackContext] = {
    playbackContext orElse createPlaybackContext()
  }

  /**
   * Creates a new playback context if this is currently possible.
   * @return an option for the new playback context
   */
  private def createPlaybackContext(): Option[PlaybackContext] = {
    if (audioBufferFilled && contextFactory.subFactories.nonEmpty) {
      playbackContext = contextFactory.createPlaybackContext(audioDataStream, currentSource.get.uri)
      playbackContext match {
        case Some(ctx) =>
          audioChunk = createChunkBuffer(ctx)
        case None =>
          enterSkipMode()
      }
      playbackContext
    } else None
  }

  /**
   * Creates the array for processing chunks of the current playback context if
   * the context is defined.
   * @param context the current context
   * @return the array buffer for the playback context
   */
  private def createChunkBuffer(context: PlaybackContext): Array[Byte] =
    new Array[Byte](context.bufferSize)

  private def bytesInAudioBuffer = audioDataStream.available()

  /**
   * Closes the playback context if it exists.
   */
  private def closePlaybackContext(): Unit = {
    playbackContext foreach { ctx =>
      ctx.line.close()
      ctx.stream.close()
    }
    playbackContext = None
  }

  /**
   * Reacts on a close request message. The actor switches to a state in which
   * it does no longer accept arbitrary messages. If currently playback is
   * ongoing, the request cannot be served immediately; rather, we have to wait
   * until the line writer actor is done.
   */
  private def handleCloseRequest(): Unit = {
    closingActor = sender()
    stopPlayback()
    context.become(closing)
    if (!audioPlaybackPending) {
      closeActor()
    }
  }

  /**
   * Actually reacts on a close request. Performs cleanup and notifies the
   * triggering actor that the close operation is complete.
   */
  private def closeActor(): Unit = {
    closePlaybackContext()
    closingActor ! CloseAck(self)
  }
}
