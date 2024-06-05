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

import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage.LineCreatorFunc
import org.apache.pekko.{NotUsed, actor as classic}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.stream.{FlowShape, Graph, Materializer, SharedKillSwitch}
import org.apache.pekko.util.ByteString

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
    * @param sourceResolverFunc the function to resolve audio sources
    * @param sinkProviderFunc   the function to create sinks for audio streams
    * @param audioStreamFactory the factory for creating audio encoding streams
    * @param pauseActor         the actor to pause playback
    * @param inMemoryBufferSize the in-memory buffer size
    * @param lineCreatorFunc    the function to create audio line objects
    * @param optKillSwitch      an optional [[SharedKillSwitch]] to cancel the
    *                           audio stream
    * @param dispatcherName     the dispatcher for writing to lines
    * @tparam SRC the type of the data identifying audio sources
    * @tparam SNK the result type of the sinks for audio streams
    */
  case class AudioStreamPlayerConfig[SRC, SNK](sourceResolverFunc: SourceResolverFunc[SRC],
                                               sinkProviderFunc: SinkProviderFunc[SRC, SNK],
                                               audioStreamFactory: AudioStreamFactory,
                                               pauseActor: ActorRef[PausePlaybackStage.PausePlaybackCommand],
                                               inMemoryBufferSize: Int = AudioEncodingStage.DefaultInMemoryBufferSize,
                                               lineCreatorFunc: LineCreatorFunc =
                                               LineWriterStage.DefaultLineCreatorFunc,
                                               optKillSwitch: Option[SharedKillSwitch] = None,
                                               dispatcherName: String = LineWriterStage.BlockingDispatcherName)

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
    given typedSystem: ActorSystem[Nothing] = system.toTyped

    given ec: ExecutionContext = system.dispatcher

    Flow[SRC].mapAsync(parallelism = 1) { src =>
        config.sourceResolverFunc(src).map(_ -> config.sinkProviderFunc(src))
      }.map { (streamSource, sink) =>
        config.audioStreamFactory.playbackDataFor(streamSource.url).map { playbackData =>
          (streamSource, sink, playbackData)
        }
      }.filter(_.isDefined)
      .map(_.get)
      .mapAsync(parallelism = 1) { (streamSource, sink, playbackData) =>
        val source = config.optKillSwitch.fold(streamSource.source) { ks =>
          streamSource.source.via(ks.flow)
        }
        source
          .via(PausePlaybackStage.pausePlaybackStage(config.pauseActor))
          .via(AudioEncodingStage(playbackData, config.inMemoryBufferSize))
          .via(LineWriterStage(config.lineCreatorFunc, config.dispatcherName))
          .runWith(sink)
      }
