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

package de.oliver_heger.linedj.platform.audio.playlist

import de.oliver_heger.linedj.platform.audio.playlist.Playlist.SongList
import de.oliver_heger.linedj.player.engine.AudioSourcePlaylistInfo

object Playlist {
  /**
    * Type definition for a list of songs. Lists of this kind are used by
    * ''Playlist'' instances.
    */
  type SongList = List[AudioSourcePlaylistInfo]
}

/**
  * A class representing a playlist.
  *
  * A playlist basically consists of two lists: the list of songs that are to
  * be played, and the list of songs that have already been played. With this
  * model it is easily possible to determine the current position in the
  * playlist and to move forwards and backwards.
  *
  * The ''pendingSongs'' list contains the songs in the order they will be
  * played. The head of the list is the current song. The ''playedSongs'' list
  * has reverse order; its head is the song that was played before the current
  * one.
  *
  * @param pendingSongs the list of songs that are to be played
  * @param playedSongs  the list of songs that have been played
  */
case class Playlist(pendingSongs: SongList, playedSongs: SongList)
