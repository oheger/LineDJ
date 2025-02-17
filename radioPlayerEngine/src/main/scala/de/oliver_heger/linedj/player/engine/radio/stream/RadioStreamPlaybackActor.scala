/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.stream

import de.oliver_heger.linedj.player.engine.AsyncAudioStreamFactory
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage.LineCreatorFunc
import de.oliver_heger.linedj.player.engine.stream.{AudioEncodingStage, AudioStreamPlayerStage, LineWriterStage}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}
import org.apache.pekko.{NotUsed, actor as classic}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * An actor implementation for managing a stream with radio sources that are
  * to be played.
  *
  * The actor manages a playlist stream where the single elements are actually
  * radio sources. The source of the stream is a queue, so new elements can be
  * added dynamically. Switching to another source means cancelling the current
  * one and then putting the new source into the source queue.
  *
  * The actor further manages an event actor and produces proper events for the
  * current radio stream or on changes of the current stream. For the radio
  * player engine, this actor manages the currently played radio source.
  */
object RadioStreamPlaybackActor:
  /**
    * The name used for the playback stream. This name is passed to the handle
    * actor when requesting a handle for a radio source. Since only one radio
    * source is played at a given time, this name can be a constant. Other 
    * parts of the radio engine dealing with stream handles must choose 
    * different names.
    */
  final val PlaybackStreamName = "radioPlayback"

  /** A default timeout setting for the playback configuration. */
  private val DefaultTimeout = Timeout(60.seconds)

  /**
    * A data class holding the configuration settings for this actor.
    *
    * @param audioStreamFactory     the factory for creating audio streams
    * @param handleActor            the actor for obtaining stream handles
    * @param eventActor             the actor for sending events
    * @param inMemoryBufferSize     the size of the in-memory buffer for audio
    *                               data
    * @param timeout                a timeout for requests sent to other
    *                               components
    * @param lineCreatorFunc        the function to create audio line objects
    * @param dispatcherName         the dispatcher for writing to lines
    * @param optStreamFactoryLimit  an optional limit to override the one
    *                               returned by the stream factory
    * @param progressEventThreshold a threshold for sending playback progress
    *                               events during radio playback; another event
    *                               is sent only after this duration
    */
  case class RadioStreamPlaybackConfig(audioStreamFactory: AsyncAudioStreamFactory,
                                       handleActor: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
                                       eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
                                       inMemoryBufferSize: Int = AudioEncodingStage.DefaultInMemoryBufferSize,
                                       timeout: Timeout = DefaultTimeout,
                                       lineCreatorFunc: LineCreatorFunc = LineWriterStage.DefaultLineCreatorFunc,
                                       dispatcherName: String = LineWriterStage.BlockingDispatcherName,
                                       optStreamFactoryLimit: Option[Int] = None,
                                       progressEventThreshold: FiniteDuration = 1.second)

  /**
    * The type of the sink for the playlist stream.
    */
  private type PlaylistSinkType = AudioStreamPlayerStage.PlaylistStreamResult[PlaybackRadioSource, Any]

  /**
    * The base trait for the commands processed by this actor implementation.
    */
  sealed trait RadioStreamPlaybackCommand

  /**
    * A command to trigger the playback actor to play a specific radio source.
    * The currently played source - if any - is stopped, and playback of the
    * new source starts after the corresponding stream has been obtained.
    *
    * @param source the radio source to be played
    */
  case class PlayRadioSource(source: RadioSource) extends RadioStreamPlaybackCommand

  /**
    * A command to stop the playback of the current radio source. If a radio
    * stream is currently active, it is canceled. Note that there is no command
    * to resume playback. Instead, another [[PlaybackRadioSource]] command has
    * to be sent.
    */
  case object StopPlayback extends RadioStreamPlaybackCommand

  /**
    * A command to stop this actor and free all resources. After processing 
    * this command, the actor instance can no longer be used.
    */
  case object Stop extends RadioStreamPlaybackCommand

  /**
    * An internal command to trigger the processing of an event received from 
    * the playlist stream. That way, the actor gets notifications when a radio
    * source is started or terminates.
    *
    * @param result the result from the playlist stream
    */
  private case class PlaylistStreamResultReceived(result: PlaylistSinkType) extends RadioStreamPlaybackCommand

  /**
    * An internal command to pass the handle of the current radio stream to the
    * actor when it becomes available.
    *
    * @param handle the handle to the radio stream
    * @param source the affected radio source
    */
  private case class RadioStreamHandleReceived(handle: RadioStreamHandle,
                                               source: PlaybackRadioSource) extends RadioStreamPlaybackCommand

  /**
    * An internal command to notify the actor about a chunk passed through the
    * audio line. This is used to generate playback progress events.
    *
    * @param chunk  the audio chunk that was processed
    * @param source the affected radio source
    */
  private case class AudioChunkProcessed(chunk: LineWriterStage.PlayedAudioChunk,
                                         source: PlaybackRadioSource) extends RadioStreamPlaybackCommand

  /**
    * An internal command to notify the actor that new metadata for the radio
    * stream has been received.
    *
    * @param metadata the metadata
    * @param source   the affected radio source
    */
  private case class MetadataReceived(metadata: ByteString,
                                      source: PlaybackRadioSource) extends RadioStreamPlaybackCommand

  /**
    * A factory trait allowing the creation of new instances of this actor
    * implementation.
    */
  trait Factory:
    /**
      * Returns the ''Behavior'' for creating new actor instances.
      *
      * @param config the configuration for the new actor instance
      * @return the ''Behavior'' to create a new instance
      */
    def apply(config: RadioStreamPlaybackConfig): Behavior[RadioStreamPlaybackCommand]
  end Factory

  /**
    * A default implementation of the [[Factory]] trait that can be used to 
    * create new actor instances.
    */
  final val behavior: Factory = config => setup(config)

  /**
    * A data class to represent the current radio source that is played by this
    * actor. In addition to the actual [[RadioSource]], this class also stores
    * a sequence number, which is increased for every new source to be played.
    * Using this, race conditions can be detected, for instance when still
    * events from a previous source come in.
    *
    * @param radioSource the [[RadioSource]] to be played
    * @param seqNo       the current sequence number
    */
  private case class PlaybackRadioSource(radioSource: RadioSource,
                                         seqNo: Long)

  /**
    * A data class holding the current state of the playback actor.
    *
    * @param seqNo                the current sequence number
    * @param streamStart          the start event for the current audio stream
    *                             if available
    * @param streamHandle         the handle to the current stream if available
    * @param sourceBytesProcessed the bytes processed for the current source
    * @param sourcePlaybackTime   the playback time for the current source
    * @param lastProgressEvent    the time when the last progress event was
    *                             sent
    * @param currentSource        the source that is currently played
    * @param pendingSource        a source that is currently waiting for its
    *                             playback to start
    * @param stopped              flag whether the actor has been stopped;
    *                             this is used to handle pending stream handles
    *                             gracefully when stopping the actor
    */
  private case class RadioPlaybackState(seqNo: Long,
                                        streamStart:
                                        Option[AudioStreamPlayerStage.PlaylistStreamResult
                                        .AudioStreamStart[PlaybackRadioSource, Any]],
                                        streamHandle: Option[RadioStreamHandle],
                                        sourceBytesProcessed: Long,
                                        sourcePlaybackTime: FiniteDuration,
                                        lastProgressEvent: FiniteDuration,
                                        currentSource: Option[PlaybackRadioSource],
                                        pendingSource: Option[PlaybackRadioSource],
                                        stopped: Boolean)

  /** Constant for the initial state of a new actor instance. */
  private val InitialPlaybackState = RadioPlaybackState(
    seqNo = 0,
    streamStart = None,
    streamHandle = None,
    sourceBytesProcessed = 0,
    sourcePlaybackTime = 0.millis,
    lastProgressEvent = 0.millis,
    currentSource = None,
    pendingSource = None,
    stopped = false
  )

  /**
    * A function setting up a new actor instance.
    *
    * @param config the configuration of the actor 
    * @return the behavior of the new instance
    */
  private def setup(config: RadioStreamPlaybackConfig): Behavior[RadioStreamPlaybackCommand] =
    Behaviors.setup[RadioStreamPlaybackCommand] { context =>
      given classic.ActorSystem = context.system.toClassic

      given Scheduler = context.system.scheduler

      given Timeout = config.timeout

      /**
        * Publishes the given event via the configured event actor.
        *
        * @param event the event to publish
        */
      def publishEvent(event: RadioEvent): Unit =
        config.eventActor ! EventManagerActor.Publish(event)

      /**
        * The function to resolve the source with audio data for the audio
        * player stage. The function obtains the handle from the manager actor
        * and also updates the playback actor.
        *
        * @param source the next radio source to be played
        * @return a [[Future]] with information about the radio source
        */
      def resolveRadioSource(source: PlaybackRadioSource): Future[AudioStreamPlayerStage.AudioStreamSource] =
        val streamName = s"${PlaybackStreamName}_${source.seqNo}"
        for
          handleResult <- config.handleActor.ask[RadioStreamHandleManagerActor.GetStreamHandleResponse] { ref =>
            RadioStreamHandleManagerActor.GetStreamHandle(
              params = RadioStreamHandleManagerActor.GetStreamHandleParameters(source.radioSource, streamName),
              replyTo = ref
            )
          }
          handle <- evaluateHandleResponse(handleResult, source)
          sources <- handle.attachOrCancel(config.timeout)
        yield createAudioStreamSource(source, sources)

      /**
        * Processes the response received from the
        * [[RadioStreamHandleManagerActor]]. Depending on the result,
        * corresponding events need to be published.
        *
        * @param response the response from the stream handle manager actor
        * @param source   the next radio source to be played
        * @return a [[Future]] with the resulting [[RadioStreamHandle]]
        */
      def evaluateHandleResponse(response: RadioStreamHandleManagerActor.GetStreamHandleResponse,
                                 source: PlaybackRadioSource): Future[RadioStreamHandle] =
        response.triedStreamHandle match
          case Success(handle) =>
            response.optLastMetadata.foreach { currentMetadata =>
              publishEvent(RadioMetadataEvent(response.source, currentMetadata))
            }
            context.self ! RadioStreamHandleReceived(handle, source)
            Future.successful(handle)
          case Failure(exception) =>
            Future.failed(exception)

      /**
        * Creates the object with information about the next audio source for
        * the playlist stream. This is derived from the audio source of the
        * attached radio stream handle. Also, the stream for processing
        * metadata is set up.
        *
        * @param source  the current radio source to be played
        * @param sources the sources for the audio and metadata streams
        * @return information about the next audio source to be played
        */
      def createAudioStreamSource(source: PlaybackRadioSource,
                                  sources: (Source[ByteString, NotUsed], Source[ByteString, NotUsed])):
      AudioStreamPlayerStage.AudioStreamSource =
        val metadataSink = Sink.foreach[ByteString] { metadata =>
          context.self ! MetadataReceived(metadata, source)
        }
        sources._2.runWith(metadataSink)

        val radioSourceUri = source.radioSource.uriWithExtension
        AudioStreamPlayerStage.AudioStreamSource(radioSourceUri, sources._1)

      /**
        * The function for creating the [[Sink]] for the next audio stream in
        * the playlist stream. This sink forwards playback progress information
        * to the playback actor.
        *
        * @param source the current radio source to be played
        * @return the [[Sink]] for the next audio stream
        */
      def createRadioStreamSink(source: PlaybackRadioSource):
      Sink[LineWriterStage.PlayedAudioChunk, Future[Any]] =
        Sink.foreach[LineWriterStage.PlayedAudioChunk] { chunk =>
          context.self ! AudioChunkProcessed(chunk, source)
        }

      /**
        * Stops playback of the current radio source if there is one.
        *
        * @param state the current state of the playback actor
        * @return an [[Option]] with the current [[RadioSource]]; if this is
        *         defined, playback was stopped
        */
      def stopPlaybackIfActive(state: RadioPlaybackState): Option[RadioSource] =
        state.streamHandle.foreach(_.cancelStream())
        state.streamStart.foreach { start =>
          context.log.info("Stopping playback of radio source: {}.", start.source)
          start.killSwitch.shutdown()
        }
        state.streamStart.map(_.source.radioSource)

      val playbackStreamSource = Source.queue[PlaybackRadioSource](1, OverflowStrategy.dropHead)
      val playbackStreamSink = Sink.foreach[PlaylistSinkType] { streamResult =>
        context.self ! PlaylistStreamResultReceived(streamResult)
      }
      val playerStageConfig = AudioStreamPlayerStage.AudioStreamPlayerConfig(
        sourceResolverFunc = resolveRadioSource,
        sinkProviderFunc = createRadioStreamSink,
        audioStreamFactory = config.audioStreamFactory,
        optPauseActor = None,
        inMemoryBufferSize = config.inMemoryBufferSize,
        optLineCreatorFunc = Some(config.lineCreatorFunc),
        optKillSwitch = None,
        dispatcherName = config.dispatcherName,
        optStreamFactoryLimit = config.optStreamFactoryLimit
      )
      val radioSourceQueue = AudioStreamPlayerStage.runPlaylistStream(
        playerStageConfig,
        playbackStreamSource,
        playbackStreamSink
      )._1

      /**
        * The message handler function for the playback actor.
        *
        * @param state the current state of the actor
        * @return the next behavior of the actor
        */
      def handle(state: RadioPlaybackState): Behavior[RadioStreamPlaybackCommand] =
        Behaviors.receiveMessage {
          case PlayRadioSource(source) =>
            context.log.info("Adding radio source to playlist: {}.", source)
            stopPlaybackIfActive(state)
            val playbackSource = PlaybackRadioSource(source, state.seqNo + 1)
            radioSourceQueue.offer(playbackSource)
            handle(
              state.copy(
                seqNo = playbackSource.seqNo,
                streamHandle = None,
                streamStart = None,
                sourceBytesProcessed = 0,
                sourcePlaybackTime = 0.millis,
                lastProgressEvent = 0.millis,
                pendingSource = Some(playbackSource)
              )
            )

          case StopPlayback if state.stopped =>
            context.log.warn("Received StopPlayback command after Stop. Ignoring.")
            Behaviors.same

          case StopPlayback =>
            stopPlaybackIfActive(state) match
              case Some(source) =>
                context.log.info("Handled StopPlayback command for source {}.", source)
                handle(
                  state.copy(seqNo = state.seqNo + 1, streamStart = None, streamHandle = None)
                )
              case None =>
                handle(state.copy(seqNo = state.seqNo + 1))

          case Stop =>
            stopPlaybackIfActive(state)
            if state.pendingSource.isDefined then
              handle(state.copy(stopped = true))
            else
              context.log.info("Stopping radio stream playback actor.")
              Behaviors.stopped

          case PlaylistStreamResultReceived(result) =>
            result match
              case start@AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamStart(source, killSwitch) =>
                if source.seqNo < state.seqNo then
                  context.log.info("Stopping radio source {}, since another source has been selected.", source)
                  killSwitch.shutdown()
                  Behaviors.same
                else
                  context.log.info("Playback starts for radio source {}.", source)
                  publishEvent(RadioSourceChangedEvent(source.radioSource))
                  handle(state.copy(streamStart = Some(start)))
              case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamEnd(source, _) =>
                context.log.info("Playback ends for radio source {} in state {}.", source, state)
                sourceEndEvent(state, source).foreach(publishEvent)
                handle(state.copy(currentSource = None))
              case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamFailure(source, exception) =>
                context.log.error("Error when playing radio source {}.", source, exception)
                publishEvent(RadioSourceErrorEvent(source.radioSource))
                handle(state.copy(currentSource = None))

          case RadioStreamHandleReceived(streamHandle, source) =>
            context.log.info("Radio stream handle available for radio source {}.", source)
            if source.seqNo == state.seqNo then
              if state.stopped then
                stopPlaybackIfActive(state)
                streamHandle.cancelStream()
                context.log.info("Stopping radio stream playback actor after canceling last audio stream.")
                Behaviors.stopped
              else
                handle(
                  state.copy(streamHandle = Some(streamHandle), currentSource = Some(source), pendingSource = None)
                )
            else
              context.log.info("Canceling stream, since there was a change in the playback state.")
              streamHandle.cancelStream()
              handle(state.copy(currentSource = None))

          case AudioChunkProcessed(chunk, source) =>
            if source.seqNo == state.seqNo then
              val nextBytesProcessed = state.sourceBytesProcessed + chunk.size
              val nextPlaybackTime = state.sourcePlaybackTime + chunk.duration
              val sendProgressEvent = checkProgressEvent(state, chunk.duration, config.progressEventThreshold)
              if sendProgressEvent then
                val progressEvent = RadioPlaybackProgressEvent(
                  source = source.radioSource,
                  bytesProcessed = nextBytesProcessed,
                  playbackTime = nextPlaybackTime
                )
                publishEvent(progressEvent)
              handle(state.copy(
                sourceBytesProcessed = nextBytesProcessed,
                sourcePlaybackTime = nextPlaybackTime,
                lastProgressEvent = if sendProgressEvent then nextPlaybackTime else state.lastProgressEvent)
              )
            else
              Behaviors.same

          case MetadataReceived(metadata, source) =>
            if source.seqNo == state.seqNo then
              val metadataEvent = RadioMetadataEvent(source.radioSource, CurrentMetadata(metadata.utf8String))
              publishEvent(metadataEvent)
            Behaviors.same
        }

      handle(InitialPlaybackState)
    }

  /**
    * Checks whether a new playback progress event should be sent given the
    * current state and playback duration.
    *
    * @param state         the current state of the actor
    * @param chunkDuration the duration of the current chunk
    * @param threshold     the threshold duration when to send event
    * @return a flag whether another event should be sent
    */
  private def checkProgressEvent(state: RadioPlaybackState,
                                 chunkDuration: FiniteDuration,
                                 threshold: FiniteDuration): Boolean =
    (state.sourcePlaybackTime + chunkDuration - state.lastProgressEvent) > threshold

  /**
    * Generates an optional event to be published for the end of the given
    * source. The function deals with different cases:
    *  - The source could have ended unexpectedly; then an error event needs to
    *    be generated.
    *  - The source was stopped because playback of another source started;
    *    then no event needs to be generated.
    *  - The source was stopped, and now playback is interrupted; then a
    *    playback stopped event needs to be generated.
    *
    * @param state  the current state of the actor
    * @param source the affected source
    * @return the option event to publish for this case
    */
  private def sourceEndEvent(state: RadioPlaybackState, source: PlaybackRadioSource): Option[RadioEvent] =
    if source.seqNo == state.seqNo && state.streamStart.isDefined then
      Some(RadioSourceErrorEvent(source.radioSource))
    else if state.currentSource.contains(source) && source.seqNo == state.seqNo - 1 then
      Some(RadioPlaybackStoppedEvent(source.radioSource))
    else
      None

  /**
    * Provides an [[ExecutionContext]] from an actor system in the context.
    *
    * @param system the actor system
    * @return the execution context
    */
  private given executionContext(using system: classic.ActorSystem): ExecutionContext = system.dispatcher
