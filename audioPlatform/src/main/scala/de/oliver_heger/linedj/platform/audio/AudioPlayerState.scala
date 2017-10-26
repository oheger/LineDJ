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

import de.oliver_heger.linedj.player.engine.AudioSourcePlaylistInfo

/**
  * A class representing the current playback state of the audio player
  * managed by the audio platform.
  *
  * An instance can be used to find out whether the audio player is currently
  * playing audio and contains information about the future and past playlist.
  * Note that the songs in the list with already played songs are in reverse
  * order; i.e. the song that has been played most recently is the head of this
  * list.
  *
  * @param pendingSongs   list of songs pending in the playlist
  * @param playedSongs    list of songs that have already been played
  * @param playbackActive flag whether playback is currently active
  * @param playlistClosed flag whether the playlist has already been closed
  */
case class AudioPlayerState(pendingSongs: List[AudioSourcePlaylistInfo],
                            playedSongs: List[AudioSourcePlaylistInfo],
                            playbackActive: Boolean,
                            playlistClosed: Boolean) {
  /**
    * Returns the current song in the playlist. This is either the song
    * currently played or - if there is none - the next one in the play list.
    * If the playlist is empty, result is ''None''.
    *
    * @return an option for the current song
    */
  def currentSong: Option[AudioSourcePlaylistInfo] = pendingSongs.headOption

  /**
    * Returns an instance that points to the next song in the playlist. The
    * current song is moved to the first position of the played list. The
    * pending list becomes its tail. If the state already points to the end of
    * the playlist, the same instance is returned.
    *
    * @return an instance with the playlist moved forwards
    */
  def moveToNext: AudioPlayerState =
    pendingSongs match {
      case h :: t =>
        copy(pendingSongs = t, playedSongs = h :: playedSongs)
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
    playedSongs match {
      case h :: t =>
        copy(playedSongs = t, pendingSongs = h :: pendingSongs)
      case _ => this
    }
}
