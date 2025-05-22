/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongTrackNoOrderingSpec:
  /** A test medium ID. */
  private val Medium = MediumID("someMediumURI", Some("someMediumPath"))

  /**
    * Creates a test song data object with the given properties.
    *
    * @param track the optional track number
    * @param title the title
    * @return the song data
    */
  private def createSong(track: Option[Int], title: String): SongData =
    SongData(
      MediaFileID(Medium, "song://" + title),
      MediaMetadata(title = Some(title), trackNumber = track, size = 20, checksum = "check"),
      title, 
      "someArtist",
      "someAlbum"
    )

/**
  * Test class for ''SongTrackNoOrdering''.
  */
class SongTrackNoOrderingSpec extends AnyFlatSpec with Matchers:

  import SongTrackNoOrderingSpec._

  "A SongTrackNoOrdering" should "have an order on track number" in:
    val s1 = createSong(Some(4), "s1")
    val s2 = createSong(Some(5), "s0")

    SongTrackNoOrdering.compare(s1, s2) should be < 0

  it should "order songs without track number after others" in:
    val s1 = createSong(Some(100), "s1")
    val s2 = createSong(None, "s0")

    SongTrackNoOrdering.compare(s1, s2) should be < 0

  it should "order songs with same track number by title" in:
    val s1 = createSong(None, "AA")
    val s2 = createSong(None, "A Kind of Magic")

    SongTrackNoOrdering.compare(s1, s2) should be > 0

  it should "order songs in a case-independent way" in:
    val s1 = createSong(None, "A 01")
    val s2 = createSong(None, "a 02")
    val s3 = createSong(None, "A 03")

    SongTrackNoOrdering.compare(s1, s2) should be < 0
    SongTrackNoOrdering.compare(s3, s2) should be > 0
  it should "return 0 for songs with equal properties" in:
    val s1 = createSong(Some(2), "s")
    val s2 = createSong(Some(2), "s")

    SongTrackNoOrdering.compare(s1, s2) should be(0)
