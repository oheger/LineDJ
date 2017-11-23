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

package de.oliver_heger.linedj.platform.audio

import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.player.engine.AudioSourcePlaylistInfo

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
                            playlistClosed: Boolean) {
  /**
    * Returns the current song in the playlist. This is either the song
    * currently played or - if there is none - the next one in the play list.
    * If the playlist is empty, result is ''None''.
    *
    * @return an option for the current song
    */
  def currentSong: Option[AudioSourcePlaylistInfo] = playlist.pendingSongs.headOption

  /**
    * Returns an instance that points to the next song in the playlist. The
    * current song is moved to the first position of the played list. The
    * pending list becomes its tail. If the state already points to the end of
    * the playlist, the same instance is returned.
    *
    * @return an instance with the playlist moved forwards
    */
  def moveToNext: AudioPlayerState =
    playlist.pendingSongs match {
      case h :: t =>
        copy(playlist = Playlist(pendingSongs = t, playedSongs = h :: playlist.playedSongs))
      case _ => this
    }

  /**
    * Returns an instance that points to the previous song in the playlist.
    * The head of the list with played songs becomes the current song. If
    * there is no previous song, the same instance is returned.
    *
    * @return an instance with the playlist moved backwards
    */
  def moveToPrev: AudioPlayerState =
    playlist.playedSongs match {
      case h :: t =>
        copy(playlist = Playlist(playedSongs = t, pendingSongs = h :: playlist.pendingSongs))
      case _ => this
    }
}
