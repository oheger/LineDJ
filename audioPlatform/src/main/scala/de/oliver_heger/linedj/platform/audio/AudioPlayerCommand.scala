/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio

import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.player.engine.facade.PlayerControl

import scala.concurrent.duration.FiniteDuration

/**
  * A trait representing a command sent to the audio player.
  *
  * Concrete sub classes of this trait can be published on the event bus. They
  * are processed by a special listener component which updates the central
  * audio player accordingly. This may in turn cause updates of the current
  * [[AudioPlayerState]].
  */
sealed trait AudioPlayerCommand

/**
  * A command which sets the playlist of the audio player.
  *
  * A currently existing playlist is replaced by the object defined in this
  * message. If necessary (if a current playlist exists), a reset of the audio
  * player is triggered. Then the pending songs are passed to the audio player.
  * Optionally, the playlist is closed.
  *
  * For the current song in the playlist offsets for the playback position and
  * time can be specified. This is useful if playback has been aborted and
  * should now be continued at the very same position.
  *
  * @param playlist       the new ''Playlist''
  * @param closePlaylist  flag whether the playlist is to be closed
  * @param positionOffset the offset in the audio stream where to start
  *                       playback
  * @param timeOffset     the time offset where to start playback
  */
case class SetPlaylist(playlist: Playlist, closePlaylist: Boolean = true,
                       positionOffset: Long = 0, timeOffset: Long = 0) extends AudioPlayerCommand

/**
  * A command which appends a list of songs to the current playlist.
  *
  * If the playlist has not yet been closed, all songs referenced by this
  * message are appended to the current playlist. Optionally, the playlist
  * can be closed then.
  *
  * With the ''activate'' flag a hint can be given to the platform whether the
  * current playlist is expected to be played immediately. In this case, the
  * new songs can be passed directly to the player engine, which might trigger
  * some actions, like triggering downloads for song files. A value of
  * '''false''' means that the playlist may be changed again before it is
  * finalized; in this case, no actions need to be taken yet. It is, however,
  * up to the audio platform to decide how to handle this flag. Typically, if
  * the playlist has already been activated, the flag is ignored, and new songs
  * will be propagated to the player engine.
  *
  * @param songs         list of songs to be appended to the playlist
  * @param closePlaylist flag whether the playlist is to be closed
  * @param activate      a hint whether new songs should become active
  */
case class AppendPlaylist(songs: PlaylistService.SongList,
                          closePlaylist: Boolean = false,
                          activate: Boolean = true) extends AudioPlayerCommand

/**
  * A command to start playback of audio files.
  *
  * If playback is currently not active, it is started now. An update event
  * with the modified state is generated. Optionally, a delay to start the
  * playback can be specified. Note that the playback state switches to active
  * immediately, even if a delay is provided.
  *
  * @param delay a delay when to start playback
  */
case class StartAudioPlayback(delay: FiniteDuration = PlayerControl.NoDelay)
  extends AudioPlayerCommand

/**
  * A command to stop playback of audio files.
  *
  * If playback is currently active, it is stopped now. An update event with
  * the modified state is generated. Optionally, a delay when to stop playback
  * can be specified. Note that the playback state switches to inactive
  * immediately, even if a delay is provided.
  *
  * @param delay a delay when to stop playback
  */
case class StopAudioPlayback(delay: FiniteDuration = PlayerControl.NoDelay)
  extends AudioPlayerCommand

/**
  * A command to skip the current audio source.
  *
  * If playback is currently active, the current audio file is skipped, and
  * playback starts with the next song in the playlist (if any).
  */
case object SkipCurrentSource extends AudioPlayerCommand
