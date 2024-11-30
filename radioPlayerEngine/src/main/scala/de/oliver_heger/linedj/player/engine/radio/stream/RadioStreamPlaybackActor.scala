/*
 * Copyright 2015-2024 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioEvent, RadioMetadataEvent, RadioPlaybackProgressEvent, RadioSource, RadioSourceChangedEvent, RadioSourceErrorEvent}
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage.LineCreatorFunc
import de.oliver_heger.linedj.player.engine.stream.{AudioEncodingStage, AudioStreamPlayerStage, LineWriterStage, PausePlaybackStage}
import org.apache.pekko.{NotUsed, actor as classic}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.{ByteString, Timeout}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

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
  private val DefaultTimeout = Timeout(5.seconds)

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
                                       progressEventThreshold: FiniteDuration = 1.second)

  /**
    * The type of the sink for the playlist stream.
    */
  private type PlaylistSinkType = AudioStreamPlayerStage.PlaylistStreamResult[RadioSource, RadioSource]

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
    * An internal command to trigger the processing of an event received from 
    * the playlist stream. That way, the actor gets notifications when a radio
    * source is started or terminates.
    *
    * @param result the result from the playlist stream
    */
  private case class PlaylistStreamResultReceived(result: PlaylistSinkType) extends RadioStreamPlaybackCommand

  /**
    * An internal command to notify the actor about a chunk passed through the
    * audio line. This is used to generate playback progress events.
    *
    * @param chunk  the audio chunk that was processed
    * @param source the affected radio source
    */
  private case class AudioChunkProcessed(chunk: LineWriterStage.PlayedAudioChunk,
                                         source: RadioSource) extends RadioStreamPlaybackCommand

  /**
    * An internal command to notify the actor that new metadata for the radio
    * stream has been received.
    *
    * @param metadata the metadata
    * @param source   the affected radio source
    */
  private case class MetadataReceived(metadata: ByteString,
                                      source: RadioSource) extends RadioStreamPlaybackCommand

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
    * A data class holding the current state of the playback actor.
    *
    * @param sourceBytesProcessed the bytes processed for the current source
    * @param sourcePlaybackTime   the playback time for the current source
    * @param lastProgressEvent    the time when the last progress event was
    *                             sent
    */
  private case class RadioPlaybackState(sourceBytesProcessed: Long,
                                        sourcePlaybackTime: FiniteDuration,
                                        lastProgressEvent: FiniteDuration)

  /** Constant for the initial state of a new actor instance. */
  private val InitialPlaybackState = RadioPlaybackState(
    sourceBytesProcessed = 0,
    sourcePlaybackTime = 0.millis,
    lastProgressEvent = 0.millis
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

      def resolveRadioSource(radioSource: RadioSource): Future[AudioStreamPlayerStage.AudioStreamSource] =
        for
          handleResult <- config.handleActor.ask[RadioStreamHandleManagerActor.GetStreamHandleResponse] { ref =>
            RadioStreamHandleManagerActor.GetStreamHandle(
              params = RadioStreamHandleManagerActor.GetStreamHandleParameters(radioSource, PlaybackStreamName),
              replyTo = ref
            )
          }
          handle <- Future.fromTry(handleResult.triedStreamHandle)
          sources <- handle.attachOrCancel(config.timeout)
        yield createAudioStreamSource(radioSource, sources)

      def createAudioStreamSource(radioSource: RadioSource,
                                  sources: (Source[ByteString, NotUsed], Source[ByteString, NotUsed])):
      AudioStreamPlayerStage.AudioStreamSource =
        val metadataSink = Sink.foreach[ByteString] { metadata =>
          context.self ! MetadataReceived(metadata, radioSource)
        }
        sources._2.runWith(metadataSink)
        AudioStreamPlayerStage.AudioStreamSource(radioSource.uri, sources._1)

      def createRadioStreamSink(radioSource: RadioSource):
      Sink[LineWriterStage.PlayedAudioChunk, Future[RadioSource]] =
        Sink.foreach[LineWriterStage.PlayedAudioChunk] { chunk =>
          context.self ! AudioChunkProcessed(chunk, radioSource)
        }.mapMaterializedValue(_.map(_ => radioSource))

      val playbackStreamSource = Source.queue[RadioSource](8)
      val playbackStreamSink = Sink.foreach[PlaylistSinkType] { streamResult =>
        context.self ! PlaylistStreamResultReceived(streamResult)
      }
      val pauseActor = context.spawn(
        PausePlaybackStage.pausePlaybackActor(PausePlaybackStage.PlaybackState.PlaybackPossible),
        s"${PlaybackStreamName}_pauseActor"
      )
      val playerStageConfig = AudioStreamPlayerStage.AudioStreamPlayerConfig(
        sourceResolverFunc = resolveRadioSource,
        sinkProviderFunc = createRadioStreamSink,
        audioStreamFactory = config.audioStreamFactory,
        pauseActor = pauseActor,
        inMemoryBufferSize = config.inMemoryBufferSize,
        lineCreatorFunc = config.lineCreatorFunc,
        optKillSwitch = None,
        dispatcherName = config.dispatcherName
      )
      val radioSourceQueue = AudioStreamPlayerStage.runPlaylistStream(
        playerStageConfig,
        playbackStreamSource,
        playbackStreamSink
      )._1

      def handle(state: RadioPlaybackState): Behavior[RadioStreamPlaybackCommand] =
        Behaviors.receiveMessage {
          case PlayRadioSource(source) =>
            context.log.info("Adding radio source to playlist: {}.", source)
            radioSourceQueue.offer(source)
            config.eventActor ! EventManagerActor.Publish(RadioSourceChangedEvent(source))
            Behaviors.same

          case PlaylistStreamResultReceived(result) =>
            result match
              case AudioStreamPlayerStage.AudioStreamStart(source, _) =>
                context.log.info("Playback starts for radio source {}.", source)
                Behaviors.same
              case AudioStreamPlayerStage.AudioStreamEnd(source) =>
                context.log.info("Playback ends for radio source {}.", source)
                val event = RadioSourceErrorEvent(source)
                config.eventActor ! EventManagerActor.Publish(event)
                Behaviors.same

          case AudioChunkProcessed(chunk, source) =>
            val nextBytesProcessed = state.sourceBytesProcessed + chunk.size
            val nextPlaybackTime = state.sourcePlaybackTime + chunk.duration
            val sendProgressEvent = checkProgressEvent(state, chunk.duration, config.progressEventThreshold)
            if sendProgressEvent then
              val progressEvent = RadioPlaybackProgressEvent(
                source = source,
                bytesProcessed = nextBytesProcessed,
                playbackTime = nextPlaybackTime
              )
              config.eventActor ! EventManagerActor.Publish(progressEvent)
            handle(state.copy(
              sourceBytesProcessed = nextBytesProcessed,
              sourcePlaybackTime = nextPlaybackTime,
              lastProgressEvent = if sendProgressEvent then nextPlaybackTime else state.lastProgressEvent)
            )

          case MetadataReceived(metadata, source) =>
            val metadataEvent = RadioMetadataEvent(source, CurrentMetadata(metadata.utf8String))
            config.eventActor ! EventManagerActor.Publish(metadataEvent)
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
    * Provides an [[ExecutionContext]] from an actor system in the context.
    *
    * @param system the actor system
    * @return the execution context
    */
  private given executionContext(using system: classic.ActorSystem): ExecutionContext = system.dispatcher
