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

package de.oliver_heger.linedj.pleditor.spi

import de.oliver_heger.linedj.client.model.SongData

/**
  * A trait for components that can reorder a playlist.
  *
  * This trait defines a service provider interface for extensions that allow
  * reordering of items in the playlist. Concrete implementations will support
  * different orders like random, sorter by artist, sorted by album, etc.
  */
trait PlaylistReorderer {
  /**
    * Returns a name for the order implemented by this object. This name is
    * displayed to the end user when he or she is prompted to select an order
    * scheme. Thus, it should be localized.
    * @return a name for the order produced by this object
    */
  def name: String

  /**
    * Reorders the given list of songs. Here the order produced by this object
    * is applied.
    * @param songs the original list of songs
    * @return the reordered list of songs
    */
  def reorder(songs: Seq[SongData]): Seq[SongData]
}
