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

package de.oliver_heger.linedj.reorder.medium

import de.oliver_heger.linedj.platform.model.SongData
import de.oliver_heger.linedj.pleditor.spi.LocalizedPlaylistReorderer

/**
  * An implementation of a reorder service which orders songs in ''medium''
  * style.
  *
  * This style means that songs are ordered by their (relative) URI which
  * represents the directory structure on the medium. This is equivalent to the
  * directory structure of the medium. If typical conventions are met, a
  * directory corresponds to an album, and the albums of an artist are grouped
  * under a common root directory with their inception year in their name. So
  * songs are ordered by artist and album.
  *
  * Note that this implementation does not use any meta data associated with a
  * song. It purely relies on file names.
  */
class PlaylistReordererMedium extends LocalizedPlaylistReorderer {
  override val resourceBundleBaseName = "ReorderMediumResources"

  override def reorder(songs: Seq[SongData]): Seq[SongData] =
    songs sortWith(_.uri < _.uri)
}
