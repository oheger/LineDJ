/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.player.engine.AudioStreamFactory.AudioStreamPlaybackData
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, LineWriterStage, PausePlaybackStage}
import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory, CompositeAsyncAudioStreamFactory}
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.headers.`Content-Disposition`
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import javax.sound.sampled.AudioFormat
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * A module providing an actor implementation that manages a playlist stream
  * and controls audio playback.
  *
  * This actor is used by the controller for the audio player engine. The 
  * controller listens for commands on the message bus and delegates them to
  * this actor.
  */
object AudioPlayerActor:
  /**
    * An enumeration class defining the external commands supported by this
    * actor implementation.
    */
  enum AudioPlayerCommand:
    /**
      * A command to add a factory for audio streams. Such factories are OSGi
      * declarative services components that can be added and removed 
      * dynamically. The actor manages the current set of available factories.
      *
      * @param factory the [[AsyncAudioStreamFactory]] to add
      */
    case AddAudioStreamFactory(factory: AsyncAudioStreamFactory)

    /**
      * A command to remove a factory for audio streams.
      *
      * @param factory the [[AsyncAudioStreamFactory]] to remove
      */
    case RemoveAudioStreamFactory(factory: AsyncAudioStreamFactory)

    /**
      * A command to append a media file to the playlist. The content of the
      * file is requested from the archive when it reaches the top position in
      * the playlist. If the playlist has already been closed, this command has
      * no effect.
      *
      * @param mediaFileID the ID of the file to be appended
      */
    case AppendToPlaylist(mediaFileID: String)

    /**
      * A command to start playback if it has been paused.
      */
    case StartPlayback

    /**
      * A command to pause playback if it is currently active.
      */
    case StopPlayback

    /**
      * A command to close the current playlist. After this command has been 
      * received, it is no longer possible to append songs to the playlist. 
      * Note that closing the playlist is necessary to make sure that the last
      * song is played. (Otherwise, playback may stall because the stream waits
      * for sufficient data to fill buffers.)
      */
    case ClosePlaylist

    /**
      * A command to stop an actor instance.
      */
    case Stop
  end AudioPlayerCommand

  /**
    * An enumeration class defining the internal commands processed by the 
    * audio player actor. These are commands the actor sends to itself.
    */
  private enum InternalCommand:
    /**
      * A command to create an audio stream for a media file. This is used by 
      * the internal [[AsyncAudioStreamFactory]] implementation provided by the
      * actor. It delegates to the factories that are added dynamically.
      *
      * @param uri           the URI of the affected media file
      * @param promiseResult the promise used to deliver the result
      */
    case CreateAudioStream(uri: String,
                           promiseResult: Promise[AudioStreamPlaybackData])
  end InternalCommand

  /**
    * Type alias for a callback function that is invoked for the results of the
    * playlist stream. This can be used to receive notifications when an audio
    * source is started or completes.
    */
  type PlaylistStreamResultCallback = AudioStreamPlayerStage.PlaylistStreamResult[String, Any] => Unit

  /**
    * Type alias for a callback function that is invoked when a chunk of audio 
    * data has been played. This can be used to track the current playback time
    * and amount of audio data that has been processed.
    */
  type PlaybackProgressCallback = LineWriterStage.PlayedAudioChunk => Unit

  /**
    * A data class that holds the configuration settings supported by an actor
    * instance. An instance of this class must be passed to the factory to
    * create a new actor instance.
    *
    * @param archiveService   the archive service
    * @param playlistCallback the callback for playlist results
    * @param progressCallback the callback for progressed audio data
    * @param lineCreatorFunc  the function to create the audio line
    */
  final case class Config(archiveService: ArchiveService,
                          playlistCallback: PlaylistStreamResultCallback,
                          progressCallback: PlaybackProgressCallback,
                          lineCreatorFunc: LineWriterStage.LineCreatorFunc = LineWriterStage.DefaultLineCreatorFunc)

  /**
    * A factory interface for creating a behavior for a new actor instance.
    */
  trait Factory:
    /**
      * Returns the [[Behavior]] for a new actor instance based on the provided
      * configuration object.
      *
      * @param config the configuration for the actor instance
      * @return the [[Behavior]] for a new actor instance
      */
    def apply(config: Config): Behavior[AudioPlayerCommand]
  end Factory

  /**
    * A default [[Factory]] instance that can be used to create new instances
    * of this actor.
    */
  final val newInstance: Factory = (config: Config) =>
    setUpBehavior(config).narrow

  /**
    * Type alias comprising all the commands handled by this actor. This 
    * includes the public and the internal commands.
    */
  private type ActorCommand = AudioPlayerCommand | InternalCommand

  /** The default sample rate used for unknown audio sources. */
  private val DefaultSampleRate = 44100.0f

  /** The default audio format used for unknown audio sources. */
  private val DefaultAudioFormat = new AudioFormat(DefaultSampleRate, 16, 2, true, false)

  /** The stream factory limit used by the default audio stream factory. */
  private val DefaultStreamFactoryLimit = AudioStreamFactory.DefaultAudioBufferSize

  /** The prefix of the URI for requesting a media file from the archive. */
  private val ArchiveDownloadURIPrefix = "/api/archive/files/"

  /** The suffix of the URI for requesting a media file from the archive. */
  private val ArchiveDownloadURISuffix = "/download?stripMetadata=true"

  /**
    * Returns a behavior for a new actor instance.
    *
    * @param config the config parameters for the new actor instance
    * @return the [[Behavior]] for the new instance
    */
  private def setUpBehavior(config: Config): Behavior[ActorCommand] =
    Behaviors.setup: context =>
      val audioStreamFactoryImpl: AsyncAudioStreamFactory = (uri: String) =>
        val promiseSource = Promise[AudioStreamPlaybackData]()
        context.self ! InternalCommand.CreateAudioStream(uri, promiseSource)
        promiseSource.future

      /**
        * Returns a source for the next media file in the playlist. This 
        * function requests the media file with the given ID from the archive
        * service.
        *
        * @param id the ID of the media file to be played next
        * @return an object with the content of this media file
        */
      def resolveAudioSource(id: String): Future[AudioStreamPlayerStage.AudioStreamSource] =
        val requestUri = ArchiveDownloadURIPrefix + id + ArchiveDownloadURISuffix
        config.archiveService.sendRequest(HttpRequest(uri = requestUri)).map: response =>
          val optFileName = response.header[`Content-Disposition`].flatMap(_.params.get("filename"))
          AudioStreamPlayerStage.AudioStreamSource(
            optFileName.getOrElse(s"$id.mp3"),
            response.entity.dataBytes
          )

      /**
        * Returns the [[Sink]] for the stream to play the next media file. This
        * is a sink which passes all chunk events from the audio line stage to
        * the callback function.
        *
        * @param id the ID of the current media file
        * @return the [[Sink]] for the current audio stream
        */
      def audioStreamSink(id: String): Sink[LineWriterStage.PlayedAudioChunk, Future[Any]] =
        Sink.foreach[LineWriterStage.PlayedAudioChunk](chunk => config.progressCallback(chunk))

      given classics.ActorSystem = context.system.toClassic

      given ExecutionContext = context.executionContext

      val pauseActor = context.spawn(
        PausePlaybackStage.pausePlaybackActor(PausePlaybackStage.PlaybackState.PlaybackPossible),
        "pausePlaybackActor"
      )
      val playlistKillSwitch = KillSwitches.shared("stopPlaylist")

      val playlistStreamConfig = AudioStreamPlayerStage.AudioStreamPlayerConfig(
        sourceResolverFunc = resolveAudioSource,
        sinkProviderFunc = audioStreamSink,
        audioStreamFactory = audioStreamFactoryImpl,
        optPauseActor = Some(pauseActor),
        optLineCreatorFunc = Some(config.lineCreatorFunc),
        optKillSwitch = Some(playlistKillSwitch)
      )
      val source = Source.queue[String](1000)
      val sink = Sink.foreach[AudioStreamPlayerStage.PlaylistStreamResult[String, Any]]: result =>
        config.playlistCallback(result)
      val playlistQueue = AudioStreamPlayerStage.runPlaylistStream(playlistStreamConfig, source, sink)._1

      /**
        * The main command handler function for this actor implementation.
        *
        * @param audioStreamFactory the current composite audio stream factory
        * @return the updated behavior
        */
      def handleAudioPlayerCommand(audioStreamFactory: CompositeAsyncAudioStreamFactory): Behavior[ActorCommand] =
        Behaviors.receiveMessage:
          case AudioPlayerCommand.AddAudioStreamFactory(factory) =>
            val nextFactory = CompositeAsyncAudioStreamFactory(factory :: audioStreamFactory.factories.toList)
            handleAudioPlayerCommand(nextFactory)

          case AudioPlayerCommand.RemoveAudioStreamFactory(factory) =>
            val nextFactories = audioStreamFactory.factories.filterNot(_ == factory)
            handleAudioPlayerCommand(CompositeAsyncAudioStreamFactory(nextFactories))

          case AudioPlayerCommand.AppendToPlaylist(mediaFileID) =>
            playlistQueue.offer(mediaFileID)
            Behaviors.same

          case AudioPlayerCommand.ClosePlaylist =>
            playlistQueue.complete()
            Behaviors.same

          case AudioPlayerCommand.StopPlayback =>
            pauseActor ! PausePlaybackStage.StopPlayback
            Behaviors.same

          case AudioPlayerCommand.StartPlayback =>
            pauseActor ! PausePlaybackStage.StartPlayback
            Behaviors.same

          case AudioPlayerCommand.Stop =>
            playlistKillSwitch.shutdown()
            Behaviors.stopped

          case InternalCommand.CreateAudioStream(uri, promiseResult) =>
            audioStreamFactory.playbackDataForAsync(uri).onComplete: triedSource =>
              promiseResult.complete(triedSource)
            Behaviors.same

      handleAudioPlayerCommand(CompositeAsyncAudioStreamFactory(Nil))
