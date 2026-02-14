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

package de.oliver_heger.linedj.player.shell

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.PausePlaybackStage.PlaybackState
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamPlayerStage, BufferedPlaylistSource, LineWriterStage, PausePlaybackStage}
import de.oliver_heger.linedj.player.shell.PlaylistStreamHandler.BufferFunc
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.stream.scaladsl.{FileIO, Sink, Source}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.headers.`Content-Disposition`
import org.apache.pekko.stream.{BoundedSourceQueue, KillSwitch, KillSwitches}
import org.apache.pekko.util.Timeout

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

object PlaylistStreamHandler:
  /**
    * Type definition for an optional configuration of a buffered source.
    */
  type OptBufferedSourceConfig = Option[BufferedPlaylistSource.BufferedPlaylistSourceConfig[String, Any]]

  /**
    * Type definition for a function that can create a configuration for a
    * buffered source. If specified on the command line, the function creates a
    * corresponding configuration, so that audio playback runs over this
    * buffer.
    */
  type BufferFunc = AudioStreamPlayerStage.AudioStreamPlayerConfig[String, Any] => OptBufferedSourceConfig
end PlaylistStreamHandler

/**
  * A helper class that manages the current playlist. It can add a new source
  * to the playlist, and pause or resume playback. The class also resolves
  * audio sources. Audio sources can either be paths to files on the local file
  * system or IDs of media files in a media archive.
  *
  * @param audioStreamFactory the [[AudioStreamFactory]]
  * @param bufferFunc         the function to configure a buffered source
  * @param optArchiveActor    optional reference to an actor for sending 
  *                           requests to a media archive
  * @param system             the implicit actor system
  * @param timeout            the timeout for HTTP requests
  */
private class PlaylistStreamHandler(audioStreamFactory: AudioStreamFactory,
                                    bufferFunc: BufferFunc,
                                    optArchiveActor: Option[ActorRef[HttpRequestSender.HttpCommand]])
                                   (using system: classic.ActorSystem,
                                    timeout: Timeout):
  /** The execution context for operations with futures. */
  private given executionContext: ExecutionContext = system.dispatcher

  /**
    * The logger. Since the log output is redirected to the [[Output]] module,
    * writing to the logger nicely integrates with other messages produced by
    * commands.
    */
  private val log = LogManager.getLogger(getClass)

  /** Stores the kill switch to cancel the current audio stream. */
  private val refCancelStream = new AtomicReference[KillSwitch]

  /** The actor to pause and resume playback. */
  private val pauseActor = system.spawn(
    PausePlaybackStage.pausePlaybackActor(PlaybackState.PlaybackPossible),
    "pauseActor"
  )

  /** The kill switch for stopping the whole playlist. */
  private val playlistKillSwitch = KillSwitches.shared("stopPlaylist")

  /** The queue for adding new audio sources to the playlist. */
  private val playlistQueue = startPlaylistStream()

  /**
    * Starts a new audio stream. If one is currently in progress, it is
    * canceled before.
    *
    * @param uri the URI to the audio stream to be played
    */
  def addToPlaylist(uri: String): Unit =
    playlistQueue.offer(uri)

  /**
    * Stops audio playback.
    */
  def stopPlayback(): Unit =
    pauseActor ! PausePlaybackStage.StopPlayback

  /**
    * Starts audio playback.
    */
  def startPlayback(): Unit =
    pauseActor ! PausePlaybackStage.StartPlayback

  /**
    * Closes the current playlist.
    */
  def closePlaylist(): Unit =
    playlistQueue.complete()

  /**
    * Shuts down this object and stops the playlist.
    */
  def shutdown(): Unit =
    skipCurrentSource()
    playlistKillSwitch.shutdown()
    pauseActor ! PausePlaybackStage.Stop

  /**
    * Cancels a currently played audio source, so that playback continues with
    * the next one in the playlist. If no audio source is currently played,
    * this command has no effect.
    */
  def skipCurrentSource(): Unit =
    Option(refCancelStream.get()).foreach { ks =>
      ks.shutdown()
    }

  /**
    * Starts the stream for the playlist and returns the queue for adding new
    * audio sources to be played.
    *
    * @return the queue for adding elements to the playlist
    */
  private def startPlaylistStream(): BoundedSourceQueue[String] =
    val config = AudioStreamPlayerStage.AudioStreamPlayerConfig(
      sourceResolverFunc = resolveAudioSource,
      sinkProviderFunc = audioStreamSink,
      audioStreamFactory = audioStreamFactory,
      optPauseActor = Some(pauseActor),
      optKillSwitch = Some(playlistKillSwitch)
    )
    val source = Source.queue[String](100)
    val sink = Sink.foreach[AudioStreamPlayerStage.PlaylistStreamResult[Any, Any]] {
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamEnd(audioSourcePath, _) =>
        refCancelStream.set(null)
        log.info("Audio stream for '{}' was completed successfully.", audioSourcePath)
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamFailure(audioSourcePath, exception) =>
        refCancelStream.set(null)
        log.error("Audio stream for '{}' failed with an exception.", audioSourcePath, exception)
      case AudioStreamPlayerStage.PlaylistStreamResult.AudioStreamStart(audioSourcePath, killSwitch) =>
        refCancelStream.set(killSwitch)
        log.info("Starting playback of '{}'.", audioSourcePath)
    }

    bufferFunc(config).map: bufferConfig =>
      log.info("Creating a buffered source with configuration: {}", bufferConfig)
      val bufferedSource = BufferedPlaylistSource(bufferConfig, source)
      val bufferedConfig = BufferedPlaylistSource.mapConfig(bufferConfig.streamPlayerConfig)
      AudioStreamPlayerStage.runPlaylistStream(bufferedConfig, bufferedSource, sink)._1
    .getOrElse:
      AudioStreamPlayerStage.runPlaylistStream(config, source, sink)._1

  /**
    * A function to resolve audio sources in the playlist. The string is 
    * checked whether it points to a local file. If so, this file is loaded and
    * played. Otherwise, and if an actor for communicating with a media archive 
    * is available, the archive is queried to download the media file.
    *
    * @param path the path to the audio file
    * @return a ''Future'' with the source to be played
    */
  private def resolveAudioSource(path: String): Future[AudioStreamPlayerStage.AudioStreamSource] =
    val localPath = Paths.get(path)
    optArchiveActor match
      case Some(archiveActor) if !Files.isRegularFile(localPath) =>
        given ActorSystem[_] = system.toTyped

        val requestUri = s"/api/archive/files/$path/download?stripMetadata=true"
        HttpRequestSender.sendRequestSuccess(archiveActor, HttpRequest(uri = requestUri)).map: result =>
          val optFileName = result.response.header[`Content-Disposition`].flatMap(_.params.get("filename"))
          AudioStreamPlayerStage.AudioStreamSource(
            optFileName.getOrElse(s"$path.mp3"),
            result.response.entity.dataBytes
          )
      case _ =>
        resolveLocalPath(localPath)

  /**
    * Resolves an audio source from a path to the local file system.
    *
    * @param path the path
    * @return a [[Future]] with the source to be played
    */
  private def resolveLocalPath(path: Path): Future[AudioStreamPlayerStage.AudioStreamSource] =
    Future:
      AudioStreamPlayerStage.AudioStreamSource(path.toString, FileIO.fromPath(path))

  /**
    * Returns the sink for the audio stream with the given path. The sink 
    * issues the path of the source when playback is done.
    *
    * @param audioSourcePath the path of the current audio source
    * @return the sink for playing a single audio stream
    */
  private def audioStreamSink(audioSourcePath: String): Sink[LineWriterStage.PlayedAudioChunk, Future[Any]] =
    Sink.last[LineWriterStage.PlayedAudioChunk]
end PlaylistStreamHandler
