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

package de.oliver_heger.linedj.reorder.randomalbum

import de.oliver_heger.linedj.platform.model.{SongAlbumOrdering, SongData}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''PlaylistReordererRandomAlbum''.
  */
class PlaylistReordererRandomAlbumSpec extends FlatSpec with Matchers {
  "A PlaylistReordererRandomAlbum" should "return a name" in {
    val reorder = new PlaylistReordererRandomAlbum

    reorder.name should not be null
  }

  it should "return the correct ordering" in {
    val reorder = new PlaylistReordererRandomAlbum

    reorder.groupOrdering should be(SongAlbumOrdering)
  }

  it should "return the album from the grouping function" in {
    val Album = "Brothers in Arms"
    val song = SongData(MediumID.UndefinedMediumID, "someURI", MediaMetaData(title = Some
    ("So Far Away"), album = Some(Album)), null)
    val reorder = new PlaylistReordererRandomAlbum

    reorder groupSong song should be(Album)
  }
}
