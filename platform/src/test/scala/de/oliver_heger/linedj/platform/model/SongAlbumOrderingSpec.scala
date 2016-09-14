/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.platform.model

import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.{FlatSpec, Matchers}

object SongAlbumOrderingSpec {
  /** A test medium ID. */
  private val Medium = MediumID("someMediumURI", Some("someMediumPath"))

  /**
    * Creates a test song data object with the given properties.
    *
    * @param year  an optional inception year
    * @param album the name of the album
    * @param title the title
    * @return the song data
    */
  private def createSong(year: Option[Int], album: String, title: String): SongData =
    SongData(Medium, "song://" + title, MediaMetaData(title = Some(title), inceptionYear = year,
      album = Some(album)), null)
}

/**
  * Test class for ''SongAlbumOrdering''.
  */
class SongAlbumOrderingSpec extends FlatSpec with Matchers {

  import SongAlbumOrderingSpec._

  "A SongAlbumOrdering" should "order songs by inception year" in {
    val s1 = createSong(Some(1983), "Crisis", "Moonlight Shadow")
    val s2 = createSong(Some(1984), "Discovery", "Talk about your Life")

    SongAlbumOrdering.compare(s1, s2) should be < 0
  }

  it should "order songs without inception year after others" in {
    val s1 = createSong(None, "unknown", "Pictures in the Dark")
    val s2 = createSong(Some(1984), "Discovery", "The Lake")

    SongAlbumOrdering.compare(s1, s2) should be > 0
  }

  it should "order songs with same inception years by album name" in {
    val s1 = createSong(Some(1975), "The Orchestral Tubular Bells", "Part I")
    val s2 = createSong(Some(1975), "Ommadawn", "Part I")

    SongAlbumOrdering.compare(s1, s2) should be > 0
  }

  it should "order songs by album name ignoring case" in {
    val s1 = createSong(None, "The Orchestral Tubular Bells", "Part I")
    val s2 = createSong(None, "ommadawn", "Part I")

    SongAlbumOrdering.compare(s1, s2) should be > 0
  }

  it should "apply standard song order for the songs on the same album" in {
    val s1 = createSong(Some(1973), "Tubular Bells", "Part II")
    val s2 = createSong(Some(1973), "Tubular Bells", "Part I")

    SongAlbumOrdering.compare(s1, s2) should be > 0
  }
}
