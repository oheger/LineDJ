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

import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongDataSpec {
  /** Test song ID. */
  private val SongID = MediaFileID(MediumID("medium", Some("settings")), "someUri")

  /** Test track number. */
  private val TrackNumber = 17

  /** Test duration. */
  private val Duration = 4444

  /** A meta data object containing all relevant information. */
  private val CompleteMetaData = MediaMetaData(trackNumber = Some(TrackNumber),
    duration = Some(Duration))
}

/**
  * Test class for ''SongData''.
  */
class SongDataSpec extends AnyFlatSpec with Matchers {

  import SongDataSpec._

  "A SongData" should "return the duration from meta data" in {
    val song = SongData(SongID, CompleteMetaData, "title", "artist", "album")

    song.getDuration should be(Duration)
    song.hasDuration shouldBe true
  }

  it should "return a special duration if undefined in meta data" in {
    val song = SongData(SongID, MediaMetaData(), "title", "artist", "album")

    song.getDuration should be(SongData.UnknownDuration)
    song.hasDuration shouldBe false
  }

  it should "return the track number from meta data" in {
    val song = SongData(SongID, CompleteMetaData, "title", "artist", "album")

    song.getTrackNumber should be(TrackNumber)
    song.hasTrackNumber shouldBe true
  }

  it should "return a special track number if undefined in meta data" in {
    val song = SongData(SongID, MediaMetaData(), "title", "artist", "album")

    song.getTrackNumber should be(SongData.UnknownTrackNumber)
    song.hasTrackNumber shouldBe false
  }
}
