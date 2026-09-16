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

package de.oliver_heger.linedj.reorder.randomartist

import de.oliver_heger.linedj.reorder.spi.MetadataExtractorTestHelper.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[PlaylistReordererRandomArtist]].
  */
class PlaylistReordererRandomArtistSpec extends AnyFlatSpec with Matchers:
  "A PlaylistReordererRandomArtist" should "return a name" in :
    val reorder = new PlaylistReordererRandomArtist

    reorder.name should not be null

  it should "return the correct ordering" in :
    val reorder = new PlaylistReordererRandomArtist
    val s1 = createFile(artist = Some("Mike Oldfield"), album = Some("Crisis"), trackNumber = Some(1))
    val s2 = createFile(
      artist = Some("Mike Oldfield"),
      album = Some("Crisis"),
      trackNumber = Some(2),
      title = Some("Moonlight Shadow")
    )

    reorder.groupOrdering(TestMetadataExtractor).compare(s1, s2) should be < 0

  it should "return the artist from the grouping function" in :
    val Artist = "Eric Clapton"
    val song = createFile(title = Some("title"), artist = Some(Artist), album = Some("album"))
    val reorder = new PlaylistReordererRandomArtist

    reorder.groupSong(TestMetadataExtractor)(song) should be(Artist)
