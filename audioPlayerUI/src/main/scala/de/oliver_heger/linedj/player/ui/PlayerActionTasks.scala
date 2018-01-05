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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio.{SetPlaylist, SkipCurrentSource, StartAudioPlayback, StopAudioPlayback}
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import net.sf.jguiraffe.gui.builder.components.model.TableHandler

/**
  * A base action task class that needs to interact with a
  * [[UIController]] instance.
  *
  * This class manages a reference to the controller. It already offers some
  * functionality to handle a default task execution.
  *
  * @param controller the ''UIController''
  */
abstract class PlayerActionTask(val controller: UIController) extends Runnable {
  override def run(): Unit = {
    playerCommand foreach { cmd =>
      controller.playerActionTriggered()
      controller.messageBus publish cmd
    }
  }

  /**
    * Returns the command to be published on the message bus on behalf of this
    * task. An implementation may decide that no command can be published.
    *
    * @return the optional message bus command
    */
  protected def playerCommand: Option[AnyRef]
}

/**
  * Task implementation for starting playback.
  *
  * @param controller the ''UIController''
  */
class StartPlaybackTask(controller: UIController) extends PlayerActionTask(controller) {
  override protected val playerCommand: Option[AnyRef] = Some(StartAudioPlayback())
}

/**
  * Task implementation for stopping playback.
  *
  * @param controller the ''UIController''
  */
class StopPlaybackTask(controller: UIController) extends PlayerActionTask(controller) {
  override protected val playerCommand: Option[AnyRef] = Some(StopAudioPlayback())
}

/**
  * Task implementation for moving to the next song in the playlist.
  *
  * @param controller the ''UIController''
  */
class NextSongTask(controller: UIController) extends PlayerActionTask(controller) {
  override protected val playerCommand: Option[AnyRef] = Some(SkipCurrentSource)
}

/**
  * Task implementation for moving backwards in the current playlist.
  *
  * The exact behavior of this task depends on the playback progress and the
  * configured threshold for moving backwards: If the playback time in the
  * current song is below the threshold and the current song is not the first
  * one in the playlist, the current position is moved to the previous song.
  * Otherwise, playback of the current song starts again.
  *
  * @param controller      the ''UIController''
  * @param config          the configuration of the audio player
  * @param playlistService the playlist service
  */
class PreviousSongTask(controller: UIController, config: AudioPlayerConfig,
                       playlistService: PlaylistService[Playlist, MediaFileID])
  extends PlayerActionTask(controller) {
  override protected def playerCommand: Option[AnyRef] = {
    nextPlaylist(controller.lastPlayerState.playlist) map { pl =>
      SetPlaylist(pl, closePlaylist = controller.lastPlayerState.playlistClosed)
    }
  }

  /**
    * Determines the updated playlist based on the playback progress in the
    * current song.
    *
    * @param currentPlaylist the current playlist
    * @return an option for the updated playlist
    */
  private def nextPlaylist(currentPlaylist: Playlist): Option[Playlist] =
    if (controller.lastProgressEvent.playbackTime < config.skipBackwardsThreshold) {
      playlistService.currentIndex(currentPlaylist) flatMap { index =>
        if (index > 0) playlistService.setCurrentSong(currentPlaylist, index - 1)
        else Some(currentPlaylist)
      }
    } else Some(currentPlaylist)
}

/**
  * Task implementation for setting a specific song as current song in the
  * playlist.
  *
  * This task obtains the current selection from the playlist table control.
  * The selected song - if defined - becomes the new current song.
  *
  * @param controller      the ''UIController''
  * @param tableHandler    the handler for the playlist table
  * @param playlistService the playlist service
  */
class GotoSongTask(controller: UIController, tableHandler: TableHandler,
                   playlistService: PlaylistService[Playlist, MediaFileID])
  extends PlayerActionTask(controller) {
  override protected def playerCommand: Option[AnyRef] = {
    val index = tableHandler.getSelectedIndex
    val state = controller.lastPlayerState
    playlistService.setCurrentSong(state.playlist, index) map { pl =>
      SetPlaylist(pl, closePlaylist = state.playlistClosed)
    }
  }
}
