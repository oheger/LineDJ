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

package de.oliver_heger.linedj.reorder.spi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''MediaFileOrderings''.
  */
class MediaFileOrderingsSpec extends AnyFlatSpec with Matchers:

  import MetadataExtractorTestHelper.*

  "A track number ordering" should "have an order on track number" in :
    val s1 = createFile(title = Some("s1"), trackNumber = Some(4))
    val s2 = createFile(title = Some("s0"), trackNumber = Some(5))

    MediaFileOrderings.songTrackNoOrdering(TestMetadataExtractor).compare(s1, s2) should be < 0

  it should "order files without track number after others" in :
    val s1 = createFile(title = Some("s1"), trackNumber = Some(100))
    val s2 = createFile(title = Some("s0"), trackNumber = None)

    MediaFileOrderings.songTrackNoOrdering(TestMetadataExtractor).compare(s1, s2) should be < 0

  it should "order files with same track number by title" in :
    val s1 = createFile(title = Some("AA"), trackNumber = None)
    val s2 = createFile(title = Some("A Kind of Magic"), trackNumber = None)

    MediaFileOrderings.songTrackNoOrdering(TestMetadataExtractor).compare(s1, s2) should be > 0

  it should "order files in a case-independent way" in :
    val s1 = createFile(title = Some("A 01"), trackNumber = None)
    val s2 = createFile(title = Some("a 02"), trackNumber = None)
    val s3 = createFile(title = Some("A 03"), trackNumber = None)

    val ordering = MediaFileOrderings.songTrackNoOrdering(TestMetadataExtractor)
    ordering.compare(s1, s2) should be < 0
    ordering.compare(s3, s2) should be > 0

  it should "return 0 for files with equal properties" in :
    val s1 = createFile(title = Some("s"), trackNumber = Some(2))
    val s2 = createFile(title = Some("s"), trackNumber = Some(2))

    MediaFileOrderings.songTrackNoOrdering(TestMetadataExtractor).compare(s1, s2) should be(0)

  "An album ordering" should "order files by inception year" in :
    val s1 = createFile(inceptionYear = Some(1983), album = Some("Crisis"), title = Some("Moonlight Shadow"))
    val s2 = createFile(inceptionYear = Some(1984), album = Some("Discovery"), title = Some("Talk about your Life"))

    MediaFileOrderings.songAlbumOrdering(TestMetadataExtractor).compare(s1, s2) should be < 0

  it should "order files without inception year after others" in :
    val s1 = createFile(inceptionYear = None, album = Some("unknown"), title = Some("Pictures in the Dark"))
    val s2 = createFile(inceptionYear = Some(1984), album = Some("Discovery"), title = Some("The Lake"))

    MediaFileOrderings.songAlbumOrdering(TestMetadataExtractor).compare(s1, s2) should be > 0

  it should "order files with same inception years by album name" in :
    val s1 = createFile(inceptionYear = Some(1975), album = Some("The Orchestral Tubular Bells"), title = Some("Part I"))
    val s2 = createFile(inceptionYear = Some(1975), album = Some("Ommadawn"), title = Some("Part I"))

    MediaFileOrderings.songAlbumOrdering(TestMetadataExtractor).compare(s1, s2) should be > 0

  it should "order files by album name ignoring case" in :
    val s1 = createFile(inceptionYear = None, album = Some("The Orchestral Tubular Bells"), title = Some("Part I"))
    val s2 = createFile(inceptionYear = None, album = Some("ommadawn"), title = Some("Part I"))

    MediaFileOrderings.songAlbumOrdering(TestMetadataExtractor).compare(s1, s2) should be > 0

  it should "apply standard song order for the files on the same album" in :
    val s1 = createFile(inceptionYear = Some(1973), album = Some("Tubular Bells"), title = Some("Part II"))
    val s2 = createFile(inceptionYear = Some(1973), album = Some("Tubular Bells"), title = Some("Part I"))

    MediaFileOrderings.songAlbumOrdering(TestMetadataExtractor).compare(s1, s2) should be > 0

  "An artist ordering" should "order files by artist name" in :
    val s1 = createFile(
      artist = Some("The Beatles"),
      album = Some("unknown"),
      title = Some("With a little help of my friends")
    )
    val s2 = createFile(artist = Some("Joe Cocker"), album = s1.album, title = s1.title)

    MediaFileOrderings.songArtistOrdering(TestMetadataExtractor).compare(s1, s2) should be > 0

  it should "ignore case when comparing artists" in :
    val s1 = createFile(
      artist = Some("joe cocker"),
      album = Some("unknown"),
      title = Some("With a little help of my friends")
    )
    val s2 = createFile(artist = Some("THE BEATLES"), album = s1.album, title = s1.title)

    MediaFileOrderings.songArtistOrdering(TestMetadataExtractor).compare(s1, s2) should be < 0

  it should "apply album criteria for files of the same artist" in :
    val s1 = createFile(
      artist = Some("Dire Straits"),
      album = Some("Brothers in Arms"),
      title = Some("Ride across the river")
    )
    val s2 = createFile(
      artist = s1.artist,
      album = Some("Love over gold"),
      title = Some("Telegraph Road")
    )
    val s3 = createFile(
      artist = s1.artist,
      album = s2.album,
      title = Some("It never rains")
    )

    val ordering = MediaFileOrderings.songArtistOrdering(TestMetadataExtractor)
    ordering.compare(s1, s2) should be < 0
    ordering.compare(s2, s3) should be > 0
