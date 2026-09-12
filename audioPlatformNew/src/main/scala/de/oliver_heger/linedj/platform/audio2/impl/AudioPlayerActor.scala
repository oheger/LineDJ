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
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, BufferedPlaylistSource, LineWriterStage, PausePlaybackStage}
import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory}
import org.apache.pekko.Done
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.headers.`Content-Disposition`
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import javax.sound.sampled.AudioFormat
import scala.concurrent.{ExecutionContext, Future}

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
      * A command to append a media file to the playlist. The content of the
      * file is requested from the archive when it reaches the top position in
      * the playlist. If the playlist has already been closed, this command has
      * no effect.
      *
      * @param mediaFileID the ID of the file to be appended
      * @param optOffset   an optional offset (in bytes) in the media file at
      *                    which playback should start; this can be used to
      *                    resume playback of a playlist at a previous position
      */
    case AppendToPlaylist(mediaFileID: String, optOffset: Option[Long] = None)

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
    * A data class that represents an element of the playlist processed by
    * this actor. In addition to the ID of the media file, an optional offset
    * can be passed, which is used to resume playback of this file at a
    * specific position.
    *
    * @param mediaFileID the ID of the media file
    * @param optOffset   an optional offset (in bytes) at which playback of
    *                    the media file should start
    */
  final case class PlaylistEntry(mediaFileID: String,
                                 optOffset: Option[Long] = None)

  /**
    * An enumeration class defining the lifecycle events of media files in the
    * playlist. Events of this type are reported via the
    * [[playlistEventCallback]] to let the outside world react on the playback
    * of the individual media files.
    */
  enum PlaylistEvent:
    /**
      * A [[PlaylistEvent]] indicating that playback of a new media file has
      * started. Instances have the ID of the media file and a [[KillSwitch]]
      * that can be used to terminate (and thus skip) the ongoing stream.
      *
      * @param mediaFileID the ID of the affected media file
      * @param killSwitch  the [[KillSwitch]] to terminate the stream
      */
    case MediaFileStarted(mediaFileID: String, killSwitch: KillSwitch)

    /**
      * A [[PlaylistEvent]] indicating that the playback of a media file has
      * completed.
      *
      * @param mediaFileID the ID of the affected media file
      */
    case MediaFileEnded(mediaFileID: String)

    /**
      * A [[PlaylistEvent]] indicating that the playback of a media file has
      * failed. Apart from the ID of the affected media file, the [[Throwable]]
      * describing the failure is available.
      *
      * @param mediaFileID the ID of the affected media file
      * @param exception   the exception that was thrown
      */
    case MediaFileFailed(mediaFileID: String, exception: Throwable)
  end PlaylistEvent

  /**
    * Type alias for a callback function that is invoked for the events of the
    * playlist. This can be used to receive notifications when a media file is
    * started or completes playback, or fails.
    */
  type PlaylistEventCallback = PlaylistEvent => Unit

  /**
    * Type alias for a callback function that is invoked when a chunk of audio 
    * data has been played. This can be used to track the current playback time
    * and amount of audio data that has been processed.
    */
  type PlaybackProgressCallback = LineWriterStage.PlayedAudioChunk => Unit

  /**
    * Type alias for a function that produces a configuration for a buffered
    * source based on the provided configuration for the playlist stream. Such
    * a function can be passed in the configuration of this actor. If it is
    * defined, a buffered source is wrapped around the playlist source. This is
    * recommended when media files are downloaded from an archive server to
    * prevent timeouts.
    */
  type BufferFunc = AudioStreamPlayerStage.AudioStreamPlayerConfig[PlaylistEntry, Any] =>
    BufferedPlaylistSource.BufferedPlaylistSourceConfig[PlaylistEntry, Any]

  /**
    * A data class that holds the configuration settings supported by an actor
    * instance. An instance of this class must be passed to the factory to
    * create a new actor instance.
    *
    * @param archiveService    the archive service
    * @param playlistCallback  the callback for playlist events
    * @param progressCallback  the callback for progressed audio data
    * @param audioStreamFactory the factory to obtain audio streams                          
    * @param lineCreatorFunc   the function to create the audio line
    * @param optBufferFunc     the optional function to create a buffered source
    * @param initPlaybackState the initial playback state
    */
  final case class Config(archiveService: ArchiveService,
                          playlistCallback: PlaylistEventCallback,
                          progressCallback: PlaybackProgressCallback,
                          audioStreamFactory: AsyncAudioStreamFactory,
                          lineCreatorFunc: LineWriterStage.LineCreatorFunc = LineWriterStage.DefaultLineCreatorFunc,
                          optBufferFunc: Option[BufferFunc] = None,
                          initPlaybackState: PausePlaybackStage.PlaybackState =
                          PausePlaybackStage.PlaybackState.PlaybackPossible)

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
    setUpBehavior(config)

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
  private def setUpBehavior(config: Config): Behavior[AudioPlayerCommand] =
    Behaviors.setup: context =>
      /**
        * Returns a source for the next media file in the playlist. This 
        * function requests the media file of the given playlist entry from the
        * archive service. If an offset is defined for the entry, it is passed
        * to the archive server as well, so that playback starts at this
        * position.
        *
        * @param entry the entry with the media file to be played next
        * @return an object with the content of this media file
        */
      def resolveAudioSource(entry: PlaylistEntry): Future[AudioStreamPlayerStage.AudioStreamSource] =
        val offsetSuffix = entry.optOffset.fold("")(offset => s"&offset=$offset")
        val requestUri = ArchiveDownloadURIPrefix + entry.mediaFileID + ArchiveDownloadURISuffix + offsetSuffix
        config.archiveService.sendRequest(HttpRequest(uri = requestUri)).map: response =>
          val optFileName = response.header[`Content-Disposition`].flatMap(_.params.get("filename"))
          AudioStreamPlayerStage.AudioStreamSource(
            optFileName.getOrElse(s"${entry.mediaFileID}.mp3"),
            response.entity.dataBytes
          )

      /**
        * Returns the [[Sink]] for the stream to play the next media file. This
        * is a sink which passes all chunk events from the audio line stage to
        * the callback function.
        *
        * @param entry the entry of the current media file
        * @return the [[Sink]] for the current audio stream
        */
      def audioStreamSink(entry: PlaylistEntry): Sink[LineWriterStage.PlayedAudioChunk, Future[Any]] =
        Sink.foreach[LineWriterStage.PlayedAudioChunk](chunk => config.progressCallback(chunk))

      given classics.ActorSystem = context.system.toClassic

      given ExecutionContext = context.executionContext

      val pauseActor = context.spawn(
        PausePlaybackStage.pausePlaybackActor(config.initPlaybackState),
        "pausePlaybackActor"
      )
      val playlistKillSwitch = KillSwitches.shared("stopPlaylist")

      val playlistStreamConfig = AudioStreamPlayerStage.AudioStreamPlayerConfig(
        sourceResolverFunc = resolveAudioSource,
        sinkProviderFunc = audioStreamSink,
        audioStreamFactory = config.audioStreamFactory,
        optPauseActor = Some(pauseActor),
        optLineCreatorFunc = Some(config.lineCreatorFunc),
        optKillSwitch = Some(playlistKillSwitch)
      )
      val source = Source.queue[PlaylistEntry](1000)

      /**
        * Creates a [[Sink]] that converts [[AudioStreamPlayerStage.PlaylistStreamResult]]
        * events to [[PlaylistEvent]] instances and forwards them to the
        * configured callback. The `sourceId` function extracts the original
        * media file ID from the stream's source type — in non-buffered mode
        * the source is already the ID, in buffered mode it is wrapped in a
        * [[BufferedPlaylistSource.SourceInBuffer]].
        *
        * @param sourceId function to extract the media file ID from the source
        * @tparam SRC the source type of the playlist stream
        * @return the sink for the playlist stream
        */
      def createPlaylistEventSink[SRC](sourceId: SRC => String):
      Sink[AudioStreamPlayerStage.PlaylistStreamResult[SRC, Any], Future[Done]] =
        Sink.foreach: result =>
          val event = result match
            case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamStart(source, killSwitch) =>
              PlaylistEvent.MediaFileStarted(sourceId(source), killSwitch)
            case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamEnd(source, _) =>
              PlaylistEvent.MediaFileEnded(sourceId(source))
            case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamFailure(source, exception) =>
              PlaylistEvent.MediaFileFailed(sourceId(source), exception)
          config.playlistCallback(event)

      val playlistQueue = config.optBufferFunc match
        case Some(bufferFunc) =>
          val bufferConfig = bufferFunc(playlistStreamConfig)
          val bufferedSource = BufferedPlaylistSource(bufferConfig, source)
          val bufferedConfig = BufferedPlaylistSource.mapConfig(bufferConfig.streamPlayerConfig)
          AudioStreamPlayerStage.runPlaylistStream(bufferedConfig, bufferedSource,
            createPlaylistEventSink(
              (src: BufferedPlaylistSource.SourceInBuffer[PlaylistEntry]) => src.originalSource.mediaFileID
            ))._1
        case None =>
          AudioStreamPlayerStage.runPlaylistStream(playlistStreamConfig, source,
            createPlaylistEventSink((entry: PlaylistEntry) => entry.mediaFileID))._1

      Behaviors.receiveMessage:
        case AudioPlayerCommand.AppendToPlaylist(mediaFileID, optOffset) =>
          playlistQueue.offer(PlaylistEntry(mediaFileID, optOffset))
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
