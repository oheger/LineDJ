/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.model

import de.oliver_heger.splaya.metadata.MediaMetaData
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''SongData''.
 */
class SongDataSpec extends FlatSpec with Matchers {
  "A SongData" should "have an order on track number" in {
    val s1 = SongData("s1", MediaMetaData(trackNumber = Some(4)))
    val s2 = SongData("s0", MediaMetaData(trackNumber = Some(5)))
    s1 should be < s2
  }

  it should "order songs without track number after others" in {
    val s1 = SongData("s1", MediaMetaData(trackNumber = Some(100)))
    val s2 = SongData("s0", MediaMetaData())
    s1 should be < s2
  }

  it should "return the title from the meta data if available" in {
    val title = "Bohemian Rhapsody"
    val s = SongData("s0", MediaMetaData(title = Some(title)))
    s.getTitle should be(title)
  }

  it should "return the last part of the URI as title as fallback" in {
    val uri = "C:\\music\\song.mp3"
    val s = SongData(uri, MediaMetaData())
    s.getTitle should be("song")
  }

  it should "extract the title from a very simple URI" in {
    val uri = "OnlyAName"
    val s = SongData(uri, MediaMetaData())
    s.getTitle should be(uri)
  }

  it should "handle URIs with prefix when extracting song titles" in {
    val uri = "song://TestSong.mp3"
    val s = SongData(uri, MediaMetaData())
    s.getTitle should be("TestSong")
  }

  it should "order songs with same track number by title" in {
    val s1 = SongData("AA", MediaMetaData(title = Some("Time")))
    val s2 = SongData("song://Queen/A Kind of Magic", MediaMetaData())
    s2 should be < s1
  }
}
