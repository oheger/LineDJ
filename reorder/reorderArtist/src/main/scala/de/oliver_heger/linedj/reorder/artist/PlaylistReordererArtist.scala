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

package de.oliver_heger.linedj.reorder.artist

import de.oliver_heger.linedj.platform.audio.model.{SongArtistOrdering, SongData}
import de.oliver_heger.linedj.pleditor.spi.LocalizedPlaylistReorderer

/**
  * An implementation of a reorder service which orders songs in ''artist''
  * style.
  *
  * This style means that songs are ordered by the artist's name. Songs
  * belonging to the same artist are ordered by typical album ordering.
  * With this reorder service it is possible to bring an arbitrary list of
  * songs into a logic order.
  */
class PlaylistReordererArtist extends LocalizedPlaylistReorderer {
  override val resourceBundleBaseName = "ReorderArtistResources"

  override def reorder(songs: Seq[SongData]): Seq[SongData] =
    songs sorted SongArtistOrdering
}
