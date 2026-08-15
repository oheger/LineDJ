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

import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongDataSpec:
  /** Test metadata with defined properties. */
  private val TestMetaData = MediaMetadata(
    checksum = "test-song-id",
    size = 123456L,
    title = Some("Test Song"),
    trackNumber = Some(7),
    duration = Some(380000),
    artist = Some("Test Artist")
  )

  /** Test metadata with undefined optional properties. */
  private val UndefinedMetaData = MediaMetadata(
    checksum = "undefined-song-id",
    size = 0
  )
end SongDataSpec

/**
  * Test class for [[SongData]].
  */
class SongDataSpec extends AnyFlatSpec, Matchers:

  import SongDataSpec.*

  "A SongData" should "return defined properties" in :
    val data = SongData(TestMetaData)

    data.getArtist should be("Test Artist")
    data.getTitle should be("Test Song")
    data.getDuration should be(380000)
    data.getTrackNumber should be(7)

  it should "return default values for undefined properties" in :
    val data = SongData(UndefinedMetaData)

    data.getArtist should be("")
    data.getTitle should be("")
    data.getTrackNumber should be < 0
    data.getDuration should be < 0
