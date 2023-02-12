/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.player.engine.actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props, typed}
import akka.util.ByteString
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest, DynamicInputStream}
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.actors.LineWriterActor.{AudioDataWritten, WriteAudioData}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor._

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.sound.sampled.{AudioFormat, AudioSystem, LineUnavailableException}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

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
    *
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
    *
    * @param factory the factory to be added
    */
  case class AddPlaybackContextFactory(factory: PlaybackContextFactory)

  /**
    * A message received by ''PlaybackActor'' telling it to remove the
    * specified ''PlaybackContextFactory''. This message should be sent when a
    * playback context factory becomes unavailable.
    *
    * @param factory the factory to be removed
    */
  case class RemovePlaybackContextFactory(factory: PlaybackContextFactory)

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

  /**
    * Creates a ''Props'' object for creating an instance of this actor class.
    *
    * @param config       the configuration of the player engine
    * @param dataSource   the actor which provides the data to be played
    * @param lineWriter   the actor that passes audio data to a line
    * @param eventActor   the actor for event generation
    * @param factoryActor the actor for creating a playback context
    * @return a ''Props'' object for creating an instance
    */
  def apply(config: PlayerConfig,
            dataSource: ActorRef,
            lineWriter: ActorRef,
            eventActor: typed.ActorRef[PlayerEvent],
            factoryActor: typed.ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand]): Props =
    Props(classOf[PlaybackActor], config, dataSource, lineWriter, eventActor, factoryActor)
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
  * reached, a [[PlaybackContext]] can be created, and playback can start. For
  * the creation of a [[PlaybackContext]] a [[PlaybackContextFactoryActor]] is
  * used.
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
  * @param config          the object with configuration settings
  * @param dataSource      the actor which provides the data to be played
  * @param lineWriterActor the actor which passes audio data to a line
  * @param eventActor      the actor for event generation
  * @param factoryActor    the actor to create the playback context
  */
class PlaybackActor(config: PlayerConfig,
                    dataSource: ActorRef,
                    lineWriterActor: ActorRef,
                    eventActor: typed.ActorRef[PlayerEvent],
                    factoryActor: typed.ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand])
  extends Actor with ActorLogging {
  /** The current audio source. */
  private var currentSource: Option[AudioSource] = None

  /** The stream which stores the currently available audio data. */
  private val audioDataStream = new DynamicInputStream

  /** The current playback context. */
  private var playbackContext: Option[PlaybackContext] = None

  /** An array for playing audio data chunk-wise. */
  private var audioChunk: Array[Byte] = _

  /**
    * Stores the time it takes to play a chunk of audio data if this can be
    * determined.
    */
  private var chunkPlaybackTime: Option[FiniteDuration] = None

  /** An actor which triggered a close request. */
  private var closingActor: ActorRef = _

  /**
    * The skip position of the current source. This is used to start playback
    * at a specific position in the audio stream.
    */
  private var skipPosition = 0L

  /**
    * The skip position for the current source stream. This is used to skip
    * the current source without decoding audio data.
    */
  private var skipStreamPosition = 0L

  /** The number of bytes processed from the current audio source so far. */
  private var bytesProcessed = 0L

  /** The number of bytes that have been played so far. */
  private var bytesPlayed = 0L

  /** The accumulated playback duration for the current source. */
  private var playbackDuration = 0.seconds

  /** The playback duration reported by the last playback progress event. */
  private var lastPlaybackEventDuration = 0.seconds

  /** A flag whether a request for audio data is pending. */
  private var audioDataPending = false

  /** A flag whether a request for playing audio data is pending. */
  private var audioPlaybackPending = false

  /** A flag whether playback is currently enabled. */
  private var playbackEnabled = false

  /** A flag whether a playback context is currently created. */
  private var playbackContextCreationPending = false

  override def receive: Receive = {
    case src: AudioSource =>
      if (currentSource.isEmpty) {
        log.info("Received audio source {}.", src.uri)
        eventActor ! AudioSourceStartedEvent(source = src)
        currentSource = Some(src)
        audioDataPending = false
        skipPosition = src.skip
        skipStreamPosition = 0
        bytesProcessed = 0
        bytesPlayed = 0
        playbackDuration = src.skipTime.seconds
        lastPlaybackEventDuration = 0.seconds
        requestAudioDataIfPossible()
      } else {
        sender() ! PlaybackProtocolViolation(src, "AudioSource is already processed!")
      }

    case res@BufferDataResult(data) =>
      if (checkAudioDataResponse(res) && currentSource.isDefined) {
        handleNewAudioData(data)
        playback()
      }

    case BufferDataComplete =>
      log.debug("Received BufferDataComplete.")
      if (checkAudioDataResponse(BufferDataComplete)) {
        audioDataStream.complete()
        assert(currentSource.isDefined)
        if (currentSourceIsInfinite || bytesInAudioBuffer == 0) {
          sourceCompleted()
        }
        playback()
      }

    case AudioDataWritten(length, duration) =>
      if (!audioPlaybackPending) {
        sender() ! PlaybackProtocolViolation(AudioDataWritten, "Unexpected AudioDataWritten message" +
          " received!")
      } else {
        audioPlaybackPending = false
        updatePlaybackProgress(length, duration)
        playback()
      }

    case LineWriterActor.LineDrained =>
      sourceCompleted()
      playback()

    case AddPlaybackContextFactory(factory) =>
      factoryActor ! PlaybackContextFactoryActor.AddPlaybackContextFactory(factory)

    case RemovePlaybackContextFactory(factory) =>
      factoryActor ! PlaybackContextFactoryActor.RemovePlaybackContextFactory(factory)

    case PlaybackContextFactoryActor.CreatePlaybackContextResult(optContext) =>
      playbackContextCreationPending = false
      playbackContext = initLine(optContext)
      playbackContext match {
        case Some(ctx) =>
          audioChunk = createChunkBuffer(ctx)
          chunkPlaybackTime = calculateChunkPlaybackTime(ctx.format)
          if (chunkPlaybackTime.isDefined) {
            // time will be updated while reaching skip position
            playbackDuration = 0.seconds
          }
          playbackAudioDataIfPossible()
        case None =>
          val source = currentSource.get
          log.warning("Could not create playback context for {}!", source.uri)
          val event = PlaybackContextCreationFailedEvent(source)
          playbackError(event)
      }

    case StartPlayback =>
      playbackEnabled = true
      playback()

    case StopPlayback =>
      stopPlayback()

    case SkipSource =>
      enterSkipMode(afterError = false)

    case CloseRequest =>
      handleCloseRequest()
  }

  /**
    * Returns a flag whether playback is currently enabled.
    *
    * @return a flag whether playback is enabled
    */
  def isPlaying: Boolean = playbackEnabled

  /**
    * An alternative ''Receive'' function which is installed when the actor is
    * to be closed, but there is still a playback operation in progress. In this
    * case, we have to wait until the playback of the current chunk is finished.
    * Then the close operation can be actually performed.
    */
  private def closing: Receive = {
    case AudioDataWritten(_, _) =>
      closeActor()
  }

  /**
    * Checks whether a received message regarding new audio data is valid in the
    * current state. If this is not the case, a protocol error message is sent.
    *
    * @return a flag whether the message is valid and can be handled
    */
  private def checkAudioDataResponse(msg: Any): Boolean = {
    if (skipStreamPosition < 0) {
      // outstanding data request after skip
      audioDataPending = false
      enterSkipMode(afterError = true)
      false
    }
    else {
      if (!audioDataPending) {
        sender() ! PlaybackProtocolViolation(msg, "Received unexpected data!")
        false
      } else {
        audioDataPending = false
        true
      }
    }
  }

  /**
    * Handles new audio data which has been sent to this actor. The
    * data has to be appended to the audio buffer - if this is allowed by the
    * current skip position.
    *
    * @param data the data to be added
    */
  private def handleNewAudioData(data: ByteString): Unit = {
    val startPos = skipStreamPosition - bytesProcessed
    if (data.length > startPos) {
      audioDataStream append data.drop(startPos.toInt)
    }
    bytesProcessed += data.length
  }

  /**
    * Updates the progress counters for the playback of the current source.
    * If necessary, a playback progress event is fired.
    *
    * @param length   the length of the current chunk
    * @param duration the playback duration of the current chunk
    */
  private def updatePlaybackProgress(length: Int, duration: FiniteDuration): Unit = {
    bytesPlayed += length
    playbackDuration += (chunkPlaybackTime getOrElse duration)
    if (playbackDuration - lastPlaybackEventDuration >= config.timeProgressThreshold) {
      lastPlaybackEventDuration = playbackDuration
      eventActor ! PlaybackProgressEvent(bytesPlayed, playbackDuration, currentSource.get)
    }
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
  private def requestAudioDataFromSourceIfPossible(): Unit = {
    def audioChunkSize: Int = if (audioChunk != null) audioChunk.length else 0

    if (!audioDataStream.completed) {
      val remainingCapacity = config.inMemoryBufferSize - bytesInAudioBuffer
      if (remainingCapacity > 0 && remainingCapacity >= audioChunkSize) {
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
          if (audioBufferFilled(ctx.bufferSize)) {
            readFromAudioStream(ctx) match {
              case Success(len) =>
                if (len > 0) {
                  val offset = scala.math.max(skipPosition - bytesPlayed, 0).toInt
                  val dataLen = len - offset
                  if (dataLen > 0) {
                    val data = ByteString(audioChunk.slice(offset, offset + dataLen))
                    lineWriterActor ! WriteAudioData(ctx.line, data)
                  } else {
                    self ! LineWriterActor.AudioDataWritten(len, 0.nanos)
                  }
                  audioPlaybackPending = true
                } else {
                  handleEmptyRead(ctx)
                }
              case Failure(exception) =>
                log.error(exception, "Error when reading audio stream!")
                playbackError(PlaybackErrorEvent(currentSource.get))
            }
          }
          requestAudioDataIfPossible()
        }
      }
    }
  }

  /**
    * Handles a read operation of audio data that yields an empty result. This
    * can either mean that the end of the audio stream is reached or indicate
    * illegal audio data. In the latter case, error handling has to be applied
    * which depends on the type of the audio source currently played.
    *
    * @param ctx the current playback context
    */
  private def handleEmptyRead(ctx: PlaybackContext): Unit = {
    log.info("Empty read.")
    if (!currentSourceIsInfinite) {
      if (audioDataStream.completed) {
        lineWriterActor ! LineWriterActor.DrainLine(ctx.line)
      } else {
        playbackError(PlaybackErrorEvent(currentSource.get))
      }
    } else {
      if (config.inMemoryBufferSize - bytesInAudioBuffer == 0) {
        log.warning("Playback stalled! Flushing buffer.")
        audioDataStream.clear()
      }
    }
  }

  /**
    * Reads data from the current audio stream of the playback context with
    * exception handling.
    *
    * @param ctx the context
    * @return a ''Try'' object with the number of bytes read
    */
  private def readFromAudioStream(ctx: PlaybackContext): Try[Int] =
    Try(ctx.stream.read(audioChunk))

  /**
    * Returns a flag whether the current source is infinite.
    *
    * @return a flag whether the current source is infinite
    */
  private def currentSourceIsInfinite: Boolean =
    currentSource.exists(_.isInfinite)

  /**
    * Checks whether the audio buffer is filled sufficiently to extract audio
    * data. This method tests the current amount of audio data available against
    * the passed in value. However, if the end of the audio source has already
    * been reached, the limit can be ignored.
    *
    * @param limit the number of bytes that must be contained in the buffer
    * @return a flag whether the audio buffer is filled sufficiently
    */
  private def audioBufferFilled(limit: Int): Boolean = {
    bytesInAudioBuffer >= limit || audioDataStream.completed
  }

  /**
    * Sets internal flags that cause the current source to be skipped.
    *
    * @param afterError a flag whether an error has occurred; in this case,
    *                   playback is stopped for an infinite source (because
    *                   there is typically no playlist to continue with)
    */
  private def enterSkipMode(afterError: Boolean): Unit = {
    currentSource foreach { s =>
      val streamCompleted = audioDataStream.completed
      audioDataStream.clear()
      if (afterError && s.isInfinite) {
        skipInfiniteSource()
      } else {
        skipStreamPosition = if (s.isInfinite) 0 else Long.MaxValue
        if (streamCompleted) {
          sourceCompleted()
        }
        requestAudioDataIfPossible()
      }
    }
  }

  /**
    * Sets internal flags to skip an infinite source. This method is called
    * when there was a problem with playback of this source. In this case,
    * playback stops after all messages for this source have been processed.
    * (We need to wait until a pending data request comes back.)
    */
  private def skipInfiniteSource(): Unit = {
    playbackEnabled = false
    if (audioDataPending) {
      skipStreamPosition = -1
    } else {
      sourceCompleted()
    }
  }

  /**
    * Marks the current source as completely processed. Playback will continue
    * with the next audio source in the playlist.
    */
  private def sourceCompleted(): Unit = {
    log.info("Finished playback of audio source {} ({} bytes read).",
      currentSource.get, bytesProcessed)
    audioDataStream.clear()
    closePlaybackContext()
    eventActor ! AudioSourceFinishedEvent(source = currentSource.get)
    currentSource = None
  }

  /**
    * Tries to obtain the current playback context if possible. If a context
    * already exists for the current source, it is directly returned. Otherwise,
    * a new one is created if and only if all preconditions are met.
    *
    * @return an option for the current playback context
    */
  private def fetchPlaybackContext(): Option[PlaybackContext] = {
    playbackContext orElse createPlaybackContext(currentSource.get)
  }

  /**
    * Creates a new playback context if this is currently possible. The
    * creation is done asynchronously by delegating to the factory actor.
    *
    * @param audioSource the current audio source
    * @return an option for the new playback context
    */
  private def createPlaybackContext(audioSource: => AudioSource): Option[PlaybackContext] = {
    lazy val source = audioSource
    if (!playbackContextCreationPending && audioBufferFilled(config.playbackContextLimit) && bytesInAudioBuffer > 0) {
      log.info("Creating playback context for {}.", source.uri)
      factoryActor ! PlaybackContextFactoryActor.CreatePlaybackContext(audioDataStream, source.uri, self)
      playbackContextCreationPending = true
    }
    None
  }

  /**
    * Calculates the playback duration of a chunk of audio data if the current
    * audio format supports this. Based on the frame size and frame rate the
    * playback time of a block of audio can be determined. This makes it
    * possible to have the current playback time pretty accurate.
    *
    * @param format the audio format
    * @return an option with the playback duration of an audio chunk
    */
  private def calculateChunkPlaybackTime(format: AudioFormat): Option[FiniteDuration] =
    if (format.getFrameRate != AudioSystem.NOT_SPECIFIED && format.getFrameSize !=
      AudioSystem.NOT_SPECIFIED)
      Some(math.round(TimeUnit.SECONDS.toNanos(1) * audioChunk.length / format.getFrameSize /
        format.getFrameRate).nanos)
    else None

  /**
    * Handles an error during playback. Sends the specified error event to the
    * event manager actor, then tries to close the current source if possible.
    *
    * @param errorEvent the error event
    */
  private def playbackError(errorEvent: PlayerEvent): Unit = {
    log.warning("Playback error!")
    eventActor ! errorEvent
    enterSkipMode(afterError = true)
  }

  /**
    * Initializes the line in the playback context. The line has to be opened
    * and started before audio can be played. This may fail; in this case,
    * result is ''None''.
    *
    * @param ctx the optional playback context
    * @return an option for the resulting playback context
    */
  private def initLine(ctx: Option[PlaybackContext]): Option[PlaybackContext] =
    ctx flatMap { c =>
      try {
        c.line.open(c.format)
        c.line.start()
        Some(c)
      } catch {
        case e: LineUnavailableException =>
          log.error(e, "Could not open line!")
          None
      }
    }


  /**
    * Creates the array for processing chunks of the current playback context if
    * the context is defined.
    *
    * @param context the current context
    * @return the array buffer for the playback context
    */
  private def createChunkBuffer(context: PlaybackContext): Array[Byte] =
    new Array[Byte](context.bufferSize)

  /**
    * Convenience method for obtaining the current number of bytes in the
    * audio buffer.
    *
    * @return the number of bytes in the audio buffer
    */
  private def bytesInAudioBuffer = audioDataStream.available()

  /**
    * Closes all objects in a playback context, ignoring exceptions.
    *
    * @param ctx the context to be closed
    */
  private[actors] def closePlaybackContext(ctx: PlaybackContext): Unit = {
    ctx.line.close()
    try {
      ctx.stream.close()
    } catch {
      case e: IOException =>
        log.error(e, "Could not close audio input stream!")
    }
  }

  /**
    * Closes the playback context if it exists.
    */
  private def closePlaybackContext(): Unit = {
    playbackContext foreach closePlaybackContext
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
