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

package de.oliver_heger.linedj.player.engine.stream

import de.oliver_heger.linedj.player.engine.AsyncAudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage.LineCreatorFunc
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.stream.*
import org.apache.pekko.util.ByteString
import org.apache.pekko.{NotUsed, actor as classic}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing a flow stage that can play a full audio source.
  *
  * Using this stage implementation, a playlist can be represented as a stream.
  * For each stream element, a source with the audio data is created, which is
  * then played. Afterward, playback continues with the next element of the
  * playlist stream.
  */
object AudioStreamPlayerStage:
  /**
    * A data class to represent a source of an audio stream that can be played
    * using this stage. In addition to the actual source of the audio data,
    * some additional metadata needs to be provided.
    *
    * @param url    the URL of the audio stream
    * @param source the source with the binary audio data
    */
  case class AudioStreamSource(url: String,
                               source: Source[ByteString, Any])

  /**
    * Definition of a function that can resolve the source for an audio stream
    * from an input parameter. A concrete implementation can interpret the
    * input parameter in a suitable way to resolve it to a corresponding
    * [[AudioStreamSource]].
    */
  type SourceResolverFunc[A] = A => Future[AudioStreamSource]

  /**
    * Definition of a function that can provide the [[Sink]] for an audio
    * player stream. The function is passed the same input as the
    * [[SourceResolverFunc]]; so it may customize the sink based on the data
    * selecting the audio data.
    */
  type SinkProviderFunc[A, B] = A => Sink[LineWriterStage.PlayedAudioChunk, Future[B]]

  /**
    * A data class to represent the configuration of a stage to play audio
    * streams. The values specified here control how audio sources are
    * resolved, and the data they contain is processed.
    *
    * @param sourceResolverFunc    the function to resolve audio sources
    * @param sinkProviderFunc      the function to create sinks for audio 
    *                              streams
    * @param audioStreamFactory    the factory for creating audio encoding 
    *                              streams
    * @param pauseActor            the actor to pause playback
    * @param inMemoryBufferSize    the in-memory buffer size
    * @param lineCreatorFunc       the function to create audio line objects
    * @param optKillSwitch         an optional [[SharedKillSwitch]] to cancel
    *                              the audio stream
    * @param dispatcherName        the dispatcher for writing to lines
    * @param optStreamFactoryLimit an optional limit to override the one 
    *                              returned by the stream factory
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the result type of the sinks for audio streams
    */
  case class AudioStreamPlayerConfig[SRC, SNK](sourceResolverFunc: SourceResolverFunc[SRC],
                                               sinkProviderFunc: SinkProviderFunc[SRC, SNK],
                                               audioStreamFactory: AsyncAudioStreamFactory,
                                               pauseActor: ActorRef[PausePlaybackStage.PausePlaybackCommand],
                                               inMemoryBufferSize: Int = AudioEncodingStage.DefaultInMemoryBufferSize,
                                               lineCreatorFunc: LineCreatorFunc =
                                               LineWriterStage.DefaultLineCreatorFunc,
                                               optKillSwitch: Option[SharedKillSwitch] = None,
                                               dispatcherName: String = LineWriterStage.BlockingDispatcherName,
                                               optStreamFactoryLimit: Option[Int] = None)

  /**
    * The root element of a type hierarchy that defines the results returned by
    * a playlist stream. Via these classes, certain lifecycle events are passed
    * downstream.
    *
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the type of results produced by the audio stream sink
    */
  sealed trait PlaylistStreamResult[+SRC, +SNK]

  /**
    * A [[PlaylistStreamResult]] indicating the start of a new audio stream.
    * An instance contains the audio source to be played. In addition, there is
    * a [[KillSwitch]] that allows terminating the audio stream. That way, this
    * audio source can be skipped to forward to the next element of the 
    * playlist.
    *
    * @param source     the audio source of the stream
    * @param killSwitch the [[KillSwitch]] to terminate the stream
    * @tparam SRC the type of the data identifying audio sources
    */
  case class AudioStreamStart[+SRC](source: SRC,
                                    killSwitch: KillSwitch) extends PlaylistStreamResult[SRC, Nothing]

  /**
    * A [[PlaylistStreamResult]] indicating that an audio stream completed.
    * From an instance, the result produced by the sink of the audio stream can
    * be obtained.
    *
    * @param result the materialized value of the audio stream sink
    * @tparam SNK the type of results produced by the audio stream sink
    */
  case class AudioStreamEnd[+SNK](result: SNK) extends PlaylistStreamResult[Nothing, SNK]

  /** The logger. */
  private val log = LogManager.getLogger()

  /**
    * A supervision strategy to be applied to playlist streams. This strategy
    * continues playback with the next element if the playback for one audio
    * source fails for whatever reason.
    */
  private val playlistStreamDecider: Supervision.Decider = _ => Supervision.resume

  /** A counter for generating unique names for kill switches. */
  private val killSwitchCounter = new AtomicInteger

  /** The prefix for the names generated for kill switches. */
  private val KillSwitchNamePrefix = "AudioStreamKillSwitch_"

  /**
    * Creates a stage for playing audio streams based on the given
    * configuration. The stage expects as input data that can be resolved by
    * the [[SourceResolverFunc]]. It yields the materialized data of the sink
    * returned by the [[SinkProviderFunc]].
    *
    * @param config the configuration for the stage
    * @param system the actor system
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the result type of the sinks for audio streams
    * @return a stage for playing audio streams
    */
  def apply[SRC, SNK](config: AudioStreamPlayerConfig[SRC, SNK])
                     (using system: classic.ActorSystem): Graph[FlowShape[SRC, SNK], NotUsed] =
    createPlaylistStream(config, config.optKillSwitch)

  /**
    * Runs a stream with a full playlist. For each element issued by the given
    * source, an audio playback stream is spawned using the given sink to
    * collect the results.
    *
    * Note that when using this function, the [[KillSwitch]] contained in the
    * configuration is used to cancel the whole playlist stream. In order to
    * cancel a single audio stream (for a specific element in the playlist),
    * the kill switch from the [[AudioStreamStart]] event fired for the stream
    * can be used.
    *
    * @param config the configuration for the audio player stage
    * @param source the source with playlist items
    * @param sink   the sink of the playlist stream
    * @param system the actor system
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the result type of the sinks for audio streams
    * @tparam RES the result type of the sink for the playlist stream
    * @tparam MAT the type of the data materialized by the source
    * @return a tuple with the materialized values of the playlist stream
    *         source and sink
    */
  def runPlaylistStream[SRC, SNK, RES, MAT](config: AudioStreamPlayerConfig[SRC, SNK],
                                            source: Source[SRC, MAT],
                                            sink: Sink[PlaylistStreamResult[SRC, SNK], RES])
                                           (using system: classic.ActorSystem): (MAT, RES) =
    val playlistStream = appendOptionalKillSwitch(source, config.optKillSwitch)
      .mapConcat { src =>
        val audioStreamKillSwitch = createKillSwitch()
        val startEvent = Future.successful(AudioStreamStart(src, audioStreamKillSwitch))
        val streamResult = runAudioStream(config, src, audioStreamKillSwitch)
        List(startEvent, streamResult)
      }
      .mapAsync(1)(identity)
      .log("playlistStream")
      .addAttributes(
        Attributes.logLevels(
          onElement = Attributes.LogLevels.Off,
          onFinish = Attributes.LogLevels.Info,
          onFailure = Attributes.LogLevels.Error
        )
      ).toMat(sink)(Keep.both)

    val supervisedStream = playlistStream.withAttributes(ActorAttributes.supervisionStrategy(playlistStreamDecider))
    supervisedStream.run()

  private def createPlaylistStream[SRC, SNK](config: AudioStreamPlayerConfig[SRC, SNK],
                                             optKillSwitch: Option[SharedKillSwitch])
                                            (using system: classic.ActorSystem):
  Graph[FlowShape[SRC, SNK], NotUsed] =
    given typedSystem: ActorSystem[Nothing] = system.toTyped

    Flow[SRC].mapAsync(parallelism = 1) { src =>
        config.sourceResolverFunc(src).map(_ -> config.sinkProviderFunc(src))
      }.mapAsync(1) { (streamSource, sink) =>
        config.audioStreamFactory.playbackDataForAsync(streamSource.url).map { playbackData =>
          Some((streamSource, sink, playbackData))
        }.recover {
          case e: AsyncAudioStreamFactory.UnsupportedUriException =>
            log.info("Unsupported audio stream URI: '{}'. Skipping.", e.uri)
            None
          case exception =>
            log.error("AudioStreamFactory threw an exception. Skipping.", exception)
            None
        }
      }.filter(_.isDefined)
      .map(_.get)
      .mapAsync(parallelism = 1) { (streamSource, sink, playbackData) =>
        val source = config.optKillSwitch.fold(streamSource.source) { ks =>
          streamSource.source.via(ks.flow)
        }
        val adjustedPlaybackData = config.optStreamFactoryLimit.fold(playbackData) { overrideLimit =>
          playbackData.copy(streamFactoryLimit = overrideLimit)
        }
        val audioStreamSource = streamSource.source
          .via(AudioEncodingStage(adjustedPlaybackData, config.inMemoryBufferSize))
          .via(PausePlaybackStage.pausePlaybackStage(config.pauseActor))
          .via(LineWriterStage(config.lineCreatorFunc, config.dispatcherName))
        appendOptionalKillSwitch(audioStreamSource, optKillSwitch)
          .runWith(sink)
      }

  /**
    * Appends the [[SharedKillSwitch]] to the given source if it is defined.
    *
    * @param source        the source
    * @param optKillSwitch the optional kill switch
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the result type of the sinks for audio streams
    * @tparam OUT the type of the source
    * @tparam MAT the type of the materialized value of the source
    * @return the source decorated with the kill switch
    */
  private def appendOptionalKillSwitch[SRC, SNK, OUT, MAT](source: Source[OUT, MAT],
                                                           optKillSwitch: Option[SharedKillSwitch]): Source[OUT, MAT] =
    optKillSwitch.fold(source) { ks =>
      source.viaMat(ks.flow)(Keep.left)
    }

  /**
    * Runs an audio stream for a specific audio source and returns the
    * ''Future'' with the result produced by the sink.
    *
    * @param config     the configuration for the audio stream
    * @param src        the audio source to be played
    * @param killSwitch the kill switch to cancel the stream
    * @param system     the actor system
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the result type of the sink for the audio stream
    * @return the ''Future'' with the result of the stream sink
    */
  private def runAudioStream[SRC, SNK](config: AudioStreamPlayerConfig[SRC, SNK],
                                       src: SRC,
                                       killSwitch: SharedKillSwitch)
                                      (using system: classic.ActorSystem): Future[AudioStreamEnd[SNK]] =
    Source.single(src)
      .via(createPlaylistStream(config, Some(killSwitch)))
      .runWith(Sink.last)
      .map(AudioStreamEnd.apply)

  /**
    * Creates a new kill switch with a unique name that can be integrated into
    * an audio stream.
    *
    * @return the new kill switch
    */
  private def createKillSwitch(): SharedKillSwitch =
    KillSwitches.shared(s"$KillSwitchNamePrefix${killSwitchCounter.incrementAndGet()}")

  /**
    * Provides an [[ExecutionContext]] from an actor system in the context.
    *
    * @param system the actor system
    * @return the execution context
    */
  private given executionContext(using system: classic.ActorSystem): ExecutionContext = system.dispatcher
