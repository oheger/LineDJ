/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.facade

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.player.engine.impl._
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerConfig}

object AudioPlayer {
  /**
    * Creates a new instance of ''AudioPlayer'' that is initialized based on
    * the passed in configuration.
    *
    * @param config the ''PlayerConfig''
    * @return the newly created ''AudioPlayer''
    */
  def apply(config: PlayerConfig): AudioPlayer = {
    val bufMan = BufferFileManager(config)
    val bufferActor = config.actorCreator(LocalBufferActor(config, bufMan), "localBufferActor")
    val readerActor = config.actorCreator(Props(classOf[SourceReaderActor], bufferActor),
      "sourceReaderActor")
    val downloadActor = config.actorCreator(SourceDownloadActor(config, bufferActor, readerActor)
      , "sourceDownloadActor")
    val lineWriterActor = createLineWriterActor(config)
    val playbackActor = config.actorCreator(PlaybackActor(config, readerActor, lineWriterActor),
      "playbackActor")
    new AudioPlayer(playbackActor, downloadActor)
  }

  /**
    * Creates the line writer actor for the audio player. This is a bit tricky
    * because this actor is to be deployed on an alternative dispatcher as it
    * executes blocking operations. An alternative dispatcher is only used if
    * one is defined in the configuration.
    *
    * @param config the ''PlayerConfig''
    * @return the reference to the line writer actor
    */
  private[facade] def createLineWriterActor(config: PlayerConfig): ActorRef =
    config.actorCreator(createLineWriterActorProps(config), "lineWriterActor")

  /**
    * Creates the properties for the line writer actor to be used by this audio
    * player.
    *
    * @param config the ''PlayerConfig''
    * @return creation properties for the line writer actor
    */
  private[facade] def createLineWriterActorProps(config: PlayerConfig): Props =
    config applyBlockingDispatcher Props[LineWriterActor]
}

/**
  * A facade on the player engine that allows playing audio files.
  *
  * This class sets up all required actors for playing a list of audio files.
  * It offers an interface for controlling playback.
  */
class AudioPlayer private(playbackActor: ActorRef, downloadActor: ActorRef) {
  /**
    * Adds the specified ''PlaybackContextFactory'' to this audio player.
    * Before audio files can be played, corresponding factories supporting
    * this audio format have to be added.
    *
    * @param factory the ''PlaybackContextFactory'' to be added
    */
  def addPlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    playbackActor ! PlaybackActor.AddPlaybackContextFactory(factory)
  }

  /**
    * Removes the specified ''PlaybackContextFactory'' from this audio player.
    * Audio files handled by this factory can no longer be played.
    *
    * @param factory the ''PlaybackContextFactory'' to be removed
    */
  def removePlaybackContextFactory(factory: PlaybackContextFactory): Unit = {
    playbackActor ! PlaybackActor.RemovePlaybackContextFactory(factory)
  }

  /**
    * Adds the specified ''AudioSourcePlaylistInfo'' object to the playlist
    * of this audio player.
    *
    * @param info the info object to be added to the playlist
    */
  def addToPlaylist(info: AudioSourcePlaylistInfo): Unit = {
    downloadActor ! info
  }

  /**
    * Adds a song to the playlist of this audio player. The corresponding
    * [[AudioSourcePlaylistInfo]] object is generated from the passed in
    * arguments.
    *
    * @param mid      the medium ID
    * @param uri      the URI of the song to be played
    * @param skip     the optional skip position
    * @param skipTime the optional skip time
    */
  def addToPlaylist(mid: MediumID, uri: String, skip: Long = 0, skipTime: Long = 0): Unit = {
    downloadActor ! AudioSourcePlaylistInfo(AudioSourceID(mid, uri), skip, skipTime)
  }

  /**
    * Closes the playlist. This method must be called after all audio sources
    * to be played have been added to the playlist; otherwise, a part of the
    * last song may not be played. After closing the playlist, no more audio
    * sources can be added.
    */
  def closePlaylist(): Unit = {
    downloadActor ! SourceDownloadActor.PlaylistEnd
  }

  /**
    * Starts audio playback. Provided that sufficient audio data has been
    * loaded, playback will start.
    */
  def startPlayback(): Unit = {
    playbackActor ! PlaybackActor.StartPlayback
  }

  /**
    * Stops audio playback. Playback is paused and can be continued by calling
    * ''startPlayback()''.
    */
  def stopPlayback(): Unit = {
    playbackActor ! PlaybackActor.StopPlayback
  }

  /**
    * Skips the current audio source. Playback continues (if enabled) with the
    * next source in the playlist (if any).
    */
  def skipCurrentSource(): Unit = {
    playbackActor ! PlaybackActor.SkipSource
  }
}
