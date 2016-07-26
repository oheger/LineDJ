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
import akka.util.Timeout
import de.oliver_heger.linedj.io.CloseAck
import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.player.engine.{AudioSourceID, AudioSourcePlaylistInfo, DelayActor, PlayerConfig}
import de.oliver_heger.linedj.player.engine.impl._

import scala.concurrent.{ExecutionContext, Future}

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
    val lineWriterActor = PlayerControl.createLineWriterActor(config)
    val eventActor = config.actorCreator(Props[EventManagerActor], "eventManagerActor")
    val delayActor = config.actorCreator(DelayActor(), "delayActor")
    val playbackActor = config.actorCreator(PlaybackActor(config, readerActor, lineWriterActor,
      eventActor), "playbackActor")
    new AudioPlayer(playbackActor, downloadActor, eventActor, delayActor,
      List(bufferActor, readerActor))
  }
}

/**
  * A facade on the player engine that allows playing audio files.
  *
  * This class sets up all required actors for playing a list of audio files.
  * It offers an interface for controlling playback.
  *
  * @param playbackActor the playback actor
  * @param downloadActor the actor handling downloads
  * @param eventManagerActor the actor for managing event listeners
  * @param delayActor the actor for delayed execution
  * @param otherActors a list with other actors to be managed by this player
  */
class AudioPlayer private(protected override val playbackActor: ActorRef,
                          downloadActor: ActorRef,
                          protected override val eventManagerActor: ActorRef,
                          protected override val delayActor: ActorRef,
                          otherActors: List[ActorRef])
  extends PlayerControl {
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

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    closeActors(playbackActor :: downloadActor :: delayActor :: otherActors)
}
