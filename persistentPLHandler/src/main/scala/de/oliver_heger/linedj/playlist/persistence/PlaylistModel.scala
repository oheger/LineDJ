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

package de.oliver_heger.linedj.playlist.persistence

/**
  * A class representing the current position in a playlist.
  *
  * The position consists of the index of the current song which is played,
  * and information about the part of the song that has been played already.
  *
  * @param index          the index of the current song in the playlist (0-based)
  * @param positionOffset the position (in bytes) within the current song
  * @param timeOffset     the time (in millis) within the current song
  */
case class CurrentPlaylistPosition(index: Int, positionOffset: Long, timeOffset: Long)
