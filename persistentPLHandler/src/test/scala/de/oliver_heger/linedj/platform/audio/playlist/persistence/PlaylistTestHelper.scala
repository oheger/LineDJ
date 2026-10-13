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
import DefaultJsonProtocol.*

/**
  * A test helper object providing functionality for dealing with playlists
  * containing test songs. This is used by multiple test classes for 
  * components that have to deal with such data.
  */
object PlaylistTestHelper:
  /**
    * Generates the ID of a test media file based on the given index.
    *
    * @param index the index of the test file
    * @return the ID of this file
    */
  def fileID(index: Int): String = s"test-file-id-$index"

  /**
    * Generates a list with the IDs of test media files in the given range.
    *
    * @param fromIndex the start index
    * @param toIndex   the end index (including)
    * @return the list with file IDs
    */
  def fileIDs(fromIndex: Int, toIndex: Int): List[String] =
    (fromIndex to toIndex).map(fileID).toList

  /**
    * Generates a test playlist item based on the given index.
    *
    * @param index the index
    * @return the test item with this index
    */
  def playlistItem(index: Int): PlaylistModel.PlaylistItem =
    PlaylistModel.PlaylistItem(fileID(index))

  /**
    * Generates a list with test playlist items with a specific size.
    *
    * @param count the number of items in the playlist
    * @return the list with playlist items
    */
  def playlistItems(count: Int): List[PlaylistModel.PlaylistItem] =
    (1 to count).map(playlistItem).toList

  extension (items: List[PlaylistModel.PlaylistItem])
    /**
      * Returns a JSON representation of this list of playlist items.
      *
      * @return the JSON representation of this list
      */
    def toJson: String =
      summon[JsonWriter[List[PlaylistModel.PlaylistItem]]].write(items).prettyPrint

  extension (position: PlaylistModel.CurrentPlaylistPosition)
    /**
      * Returns a JSON representation of this playlist position.
      *
      * @return the JSON representation of this position
      */
    def toJson: String =
      summon[JsonWriter[PlaylistModel.CurrentPlaylistPosition]].write(position).prettyPrint
