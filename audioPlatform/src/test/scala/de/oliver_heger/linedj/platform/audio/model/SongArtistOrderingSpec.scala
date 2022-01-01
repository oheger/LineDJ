/*
 * Copyright 2015-2022 The Developers Team.
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

object SongArtistOrderingSpec {
  /** A test medium ID. */
  private val Medium = MediumID("someTestMediumURI", Some("someTestMediumPath"))

  /**
    * Creates a test song data object with the given properties.
    *
    * @param artist the artist of the song
    * @param album  the name of the album
    * @param title  the title
    * @return the song data
    */
  private def createSong(artist: String, album: String, title: String): SongData =
    SongData(MediaFileID(Medium, "song://" + title),
      MediaMetaData(title = Some(title), album = Some(album), artist = Some(artist)),
      title, artist, album)
}

/**
  * Test class for ''SongArtistOrdering''.
  */
class SongArtistOrderingSpec extends AnyFlatSpec with Matchers {

  import SongArtistOrderingSpec._

  "A SongArtistOrdering" should "order songs by artist name" in {
    val s1 = createSong("The Beatles", "unknown", "With a little help of my friends")
    val s2 = createSong("Joe Cocker", s1.getAlbum(), s1.getTitle())

    SongArtistOrdering.compare(s1, s2) should be > 0
  }

  it should "ignore case when comparing artists" in {
    val s1 = createSong("joe cocker", "unknown", "With a little help of my friends")
    val s2 = createSong("THE BEATLES", s1.getAlbum(), s1.getTitle())

    SongArtistOrdering.compare(s1, s2) should be < 0
  }

  it should "apply album criteria for songs of the same artist" in {
    val s1 = createSong("Dire Straits", "Brothers in Arms", "Ride across the river")
    val s2 = createSong(s1.getArtist(), "Love over gold", "Telegraph Road")
    val s3 = createSong(s1.getArtist(), s2.getAlbum(), "It never rains")

    SongArtistOrdering.compare(s1, s2) should be < 0
    SongArtistOrdering.compare(s2, s3) should be > 0
  }
}
