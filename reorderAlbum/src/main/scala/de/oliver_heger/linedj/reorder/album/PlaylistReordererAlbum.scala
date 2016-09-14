/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.reorder.album

import de.oliver_heger.linedj.platform.model.{SongAlbumOrdering, SongData}
import de.oliver_heger.linedj.pleditor.spi.LocalizedPlaylistReorderer

/**
  * An implementation of a reorder service which orders songs in ''album''
  * style.
  *
  * This style means that songs are ordered by the album's inception year and
  * its name. This is appropriate for ordering the songs of an artist. It can
  * also be used to bring songs in a temporal order.
  */
class PlaylistReordererAlbum extends LocalizedPlaylistReorderer {
  override val resourceBundleBaseName = "ReorderAlbumResources"

  override def reorder(songs: Seq[SongData]): Seq[SongData] =
    songs sorted SongAlbumOrdering
}
