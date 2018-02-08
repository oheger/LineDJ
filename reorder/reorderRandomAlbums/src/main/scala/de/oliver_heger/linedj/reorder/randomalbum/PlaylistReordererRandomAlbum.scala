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

package de.oliver_heger.linedj.reorder.randomalbum

import de.oliver_heger.linedj.platform.audio.model.{SongAlbumOrdering, SongData}
import de.oliver_heger.linedj.pleditor.spi.RandomGroupingPlaylistReorderer

/**
  * An implementation of a reorder service which orders songs in ''album''
  * style.
  *
  * This style means that songs are ordered based on the album (i.e. inception
  * year and name). Songs belonging to the same album are ordered by their
  * track number (and name if there are ambiguities). This reorder service
  * produces quite interesting playlist because albums of different artists are
  * mixed.
  */
class PlaylistReordererRandomAlbum extends RandomGroupingPlaylistReorderer[String] {
  override val resourceBundleBaseName = "ReorderRandomAlbumResources"

  override val groupOrdering: SongAlbumOrdering.type = SongAlbumOrdering

  override def groupSong(song: SongData): String = song.getAlbum
}
