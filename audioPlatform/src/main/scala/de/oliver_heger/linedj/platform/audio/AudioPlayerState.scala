/*
 * Copyright 2015-2022 The Developers Team.
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

object AudioPlayerState {
  /**
    * An initial audio player state that can be assumed at application startup.
    * No playlist has been set, and all flags are set to initial values.
    */
  val Initial = AudioPlayerState(Playlist(Nil, Nil), playbackActive = false,
    playlistClosed = false, playlistSeqNo = PlaylistService.SeqNoInitial,
    playlistActivated = false)
}

/**
  * A class representing the current playback state of the audio player
  * managed by the audio platform.
  *
  * An instance can be used to find out whether the audio player is currently
  * playing audio and contains information about the songs that have already
  * been played or are about to be played in form of a [[Playlist]].
  *
  * @param playlist   the current ''Playlist''
  * @param playlistSeqNo the sequence number of the
  * @param playbackActive flag whether playback is currently active
  * @param playlistClosed flag whether the playlist has already been closed
  */
case class AudioPlayerState(playlist: Playlist,
                            playlistSeqNo: Int,
                            playbackActive: Boolean,
                            playlistClosed: Boolean,
                            playlistActivated: Boolean)
