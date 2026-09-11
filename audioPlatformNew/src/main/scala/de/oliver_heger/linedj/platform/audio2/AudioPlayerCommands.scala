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

package de.oliver_heger.linedj.platform.audio2

import de.oliver_heger.linedj.platform.audio2.playlist.Playlist

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * An enumeration defining the commands supported by the audio player engine
  * provided by the audio platform.
  *
  * Concrete elements of this enum can be published on the event bus. They
  * are processed by a special listener component which updates the central
  * audio player accordingly.
  */
enum AudioPlayerCommands:
  /**
    * A command which appends a list of songs to the current playlist if it has
    * not yet been closed.
    *
    * @param songIDs list of songs to be appended to the playlist
    */
  case AppendPlaylist(songIDs: Iterable[String])

  /**
    * A command which sets the playlist of the audio player.
    *
    * A currently existing playlist is replaced by the object defined in this
    * message. If necessary (i.e., if a current playlist exists), a reset of
    * the audio player is triggered. Then the pending songs are passed to the
    * audio player. Optionally, the playlist is closed.
    *
    * For the current song in the playlist offsets for the playback position and
    * time can be specified. This is useful if playback has been aborted and
    * should now be continued at the very same position.
    *
    * @param playlist       the new ''Playlist''
    * @param closePlaylist  flag whether the playlist is to be closed
    * @param positionOffset the offset in the audio stream where to start
    *                       playback
    *
    * @param timeOffset     the time offset where to start playback
    */
  case SetPlaylist(playlist: Playlist,
                   closePlaylist: Boolean = true,
                   positionOffset: Long = 0,
                   timeOffset: FiniteDuration = 0.seconds)

  /**
    * A command to start playback of audio files.
    *
    * If playback is currently not active, it is started now. An update event
    * with the modified state is generated.
    */
  case StartAudioPlayback

  /**
    * A command to stop playback of audio files.
    *
    * If playback is currently active, it is stopped now. An update event with
    * the modified state is generated.
    */
  case StopAudioPlayback

  /**
    * A command to skip the current audio source.
    *
    * If playback is currently active, the current audio file is skipped, and
    * playback starts with the next song in the playlist (if any).
    */
  case SkipCurrentSource
