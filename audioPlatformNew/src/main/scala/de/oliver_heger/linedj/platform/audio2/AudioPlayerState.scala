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
import de.oliver_heger.linedj.platform.audio2.playlist.PlaylistService

import scala.concurrent.duration.FiniteDuration

object AudioPlayerState:
  /**
    * An initial audio player state that can be assumed at application startup.
    * No playlist has been set, and all flags are set to initial values.
    */
  final val Initial = AudioPlayerState(
    playlist = Playlist(Nil, Nil),
    playlistSeqNo = PlaylistService.SeqNoInitial,
    playbackActive = false,
    playlistClosed = false,
    playlistActivated = false
  )
end AudioPlayerState

/**
  * A class representing the current playback state of the audio player
  * managed by the audio platform.
  *
  * An instance contains information about the songs that have already been
  * played or are about to be played in form of a [[Playlist]]. It also holds
  * several flags about the state of the audio playback and the playlist.
  *
  * @param playlist          the current [[Playlist]]
  * @param playlistSeqNo     the sequence number of the playlist
  * @param playbackActive    flag whether playback is currently active
  * @param playlistClosed    flag whether the playlist has already been closed
  * @param playlistActivated flag whether the playlist was passed to the player
  */
final case class AudioPlayerState(playlist: Playlist,
                                  playlistSeqNo: Int,
                                  playbackActive: Boolean,
                                  playlistClosed: Boolean,
                                  playlistActivated: Boolean)

/**
  * A data class to represent the progress of the playback of the currently
  * played media file.
  *
  * Instances of this class are published on the system message bus 
  * periodically while audio playback is active. So, interested components can
  * keep track on the playback and update themselves (or the UI) accordingly.
  *
  * @param bytesProcessed the number of bytes processed in the current file
  * @param playbackTime   the playback time within the current file
  */
final case class PlaybackProgress(bytesProcessed: Long,
                                  playbackTime: FiniteDuration)
