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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import spray.json.*
import spray.json.DefaultJsonProtocol.*

/**
  * An object defining data classes to represent elements in the playlist and
  * related data as well as formats for JSON conversion.
  */
object PlaylistModel:
  /**
    * A data class representing the data to be stored for an element contained
    * in the playlist.
    *
    * @param fileID the ID of the media file to be played
    */
  final case class PlaylistItem(fileID: String)

  /**
    * A class representing the current position in a playlist.
    *
    * The position consists of the index of the current song which is played,
    * and information about the part of the song that has been played already.
    *
    * @param index    the index of the current song in the playlist (0-based)
    * @param position the position (in bytes) within the current song
    * @param time     the time (in millis) within the current song
    */
  final case class CurrentPlaylistPosition(index: Int, position: Long, time: Long)

  /** A format for parsing persistent playlist items. */
  given RootJsonFormat[PlaylistItem] = jsonFormat1(PlaylistItem.apply)

  /** A format for parsing the playlist position. */
  given RootJsonFormat[CurrentPlaylistPosition] = jsonFormat3(CurrentPlaylistPosition.apply)
