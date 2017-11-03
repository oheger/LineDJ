/*
 * Copyright 2015-2017 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.impl.PlayerFacadeActor.{TargetDownloadActor, TargetPlaybackActor}
import de.oliver_heger.linedj.player.engine.impl._
import de.oliver_heger.linedj.player.engine.{AudioSourcePlaylistInfo, PlayerConfig}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}

import scala.concurrent.duration.FiniteDuration
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
    val lineWriterActor = PlayerControl.createLineWriterActor(config)
    val eventActor = config.actorCreator(Props[EventManagerActor], "eventManagerActor")
    val facadeActor = config.actorCreator(PlayerFacadeActor(config, eventActor, lineWriterActor),
      "playerFacadeActor")
    new AudioPlayer(facadeActor, eventActor)
  }
}

/**
  * A facade on the player engine that allows playing audio files.
  *
  * This class sets up all required actors for playing a list of audio files.
  * It offers an interface for controlling playback.
  *
  * @param facadeActor       the player facade actor
  * @param eventManagerActor the actor for managing event listeners
  */
class AudioPlayer private(facadeActor: ActorRef,
                          protected override val eventManagerActor: ActorRef)
  extends PlayerControl {
  /**
    * Adds the specified ''AudioSourcePlaylistInfo'' object to the playlist
    * of this audio player.
    *
    * @param info the info object to be added to the playlist
    */
  def addToPlaylist(info: AudioSourcePlaylistInfo): Unit = {
    invokeFacadeActor(info, TargetDownloadActor)
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
    invokeFacadeActor(AudioSourcePlaylistInfo(MediaFileID(mid, uri), skip, skipTime),
      TargetDownloadActor)
  }

  /**
    * Closes the playlist. This method must be called after all audio sources
    * to be played have been added to the playlist; otherwise, a part of the
    * last song may not be played. After closing the playlist, no more audio
    * sources can be added.
    */
  def closePlaylist(): Unit = {
    invokeFacadeActor(SourceDownloadActor.PlaylistEnd, TargetDownloadActor)
  }

  /**
    * Resets the player engine. This stops playback and clears the current
    * playlist. Afterwards, new audio files to be played can be added again
    * to the playlist.
    */
  def reset(): Unit = {
    facadeActor ! PlayerFacadeActor.ResetEngine
  }

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[Seq[CloseAck]] =
    closeActors(List(facadeActor))

  /**
    * @inheritdoc This implementation propagates the message via the facade
    *             actor.
    */
  override protected def invokePlaybackActor(msg: Any, delay: FiniteDuration): Unit = {
    invokeFacadeActor(msg, TargetPlaybackActor, delay)
  }

  /**
    * Helper method to send a message to the facade actor.
    *
    * @param msg    the message to be sent
    * @param target the receiver of the message
    * @param delay  a delay
    */
  private def invokeFacadeActor(msg: Any, target: PlayerFacadeActor.TargetActor,
                                delay: FiniteDuration = PlayerControl.NoDelay): Unit = {
    facadeActor ! PlayerFacadeActor.Dispatch(msg, target, delay)
  }
}
