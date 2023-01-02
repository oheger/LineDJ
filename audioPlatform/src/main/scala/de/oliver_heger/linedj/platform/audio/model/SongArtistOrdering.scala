/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.model

/**
  * An ''Ordering'' implementation for [[SongData]] which orders songs based on
  * the artists they belong to.
  *
  * This ordering can be used for instance to bring an arbitrary list of songs
  * into a logic order. It basically sorts songs by the artist name (ignoring
  * case). Songs belonging to the same artist are ordered by
  * [[SongAlbumOrdering]].
  */
object SongArtistOrdering extends Ordering[SongData] {
  override def compare(x: SongData, y: SongData): Int = {
    val c = x.artist compareToIgnoreCase y.artist
    if (c != 0) c
    else SongAlbumOrdering.compare(x, y)
  }
}
