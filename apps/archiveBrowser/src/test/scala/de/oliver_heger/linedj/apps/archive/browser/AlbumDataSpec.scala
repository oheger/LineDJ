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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object AlbumDataSpec:
  /** Test album info with defined properties. */
  private val TestAlbumInfo = ArchiveModel.AlbumInfo(
    id = "test-album-id",
    albumName = "Test Album",
    artistId = "test-artist-id"
  )
end AlbumDataSpec

/**
  * Test class for [[AlbumData]].
  */
class AlbumDataSpec extends AnyFlatSpec, Matchers:

  import AlbumDataSpec.*

  "An AlbumData" should "return defined properties" in :
    val data = AlbumData(TestAlbumInfo, "Test Artist")

    data.getTitle should be("Test Album")
    data.getArtist should be("Test Artist")
