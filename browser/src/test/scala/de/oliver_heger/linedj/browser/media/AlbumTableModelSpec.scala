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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object AlbumTableModelSpec {
  /** Constant for an album key.*/
  private val Album1 = AlbumKey("Queen", "A Night at the Opera")

  /** Constant for another album.*/
  private val Album2 = AlbumKey("Supertramp", "Crisis? What Crisis?")

  /** Constant for a test medium ID. */
  private val Medium = MediumID("someURI", Some("somePath"))

  /**
   * Creates a song data object with the specified data.
   * @param name the song name
   * @param track the track number
   * @return the song data object
   */
  private def song(name: String, track: Int): SongData =
    SongData(MediaFileID(Medium, "song://" + name),
      MediaMetaData(title = Some(name), trackNumber = Some(track)),
      name, "someArtist", "someAlbum")

  /**
   * Adds the given songs to a model.
   * @param model the model
   *              @param key the album key
   * @param songs the songs to be added
   * @return the resulting table model
   */
  private def appendSongs(model: AlbumTableModel = AlbumTableModel.empty)
                         (key: AlbumKey, songs: SongData*): AlbumTableModel =
  songs.foldLeft(model)((m, s) => m.add(key, s))
}

/**
 * Test class for ''AlbumTableModel''.
 */
class AlbumTableModelSpec extends AnyFlatSpec with Matchers {
  import AlbumTableModelSpec._
  "An AlbumTableModel" should "return an empty sequence for unknown data" in {
    AlbumTableModel.empty songsFor AlbumKey("Unknown", "Unknown, too") shouldBe empty
  }

  it should "allow appending songs in order" in {
    val song1 = song("You are my best friend", 4)
    val song2 = song("39", 5)
    val song3 = song("Bohemian Rhapsody", 11)

    val model = appendSongs()(Album1, song1, song2, song3)
    model songsFor Album1 should contain inOrderOnly(song1, song2, song3)
  }

  it should "support multiple albums" in {
    val song1 = song("You are my best friend", 4)
    val song2 = song("39", 5)
    val song3 = song("Bohemian Rhapsody", 11)
    val song4 = song("Sister Moonshine", 2)
    val song5 = song("A Soapbox opera", 4)

    val m1 = appendSongs()(Album1, song1, song2)
    val m2 = appendSongs(m1)(Album2, song4, song5)
    val model = appendSongs(m2)(Album1, song3)
    model songsFor Album1 should contain inOrderOnly(song1, song2, song3)
    model songsFor Album2 should contain inOrderOnly(song4, song5)
  }

  it should "allow creating an instance with a sequence of data" in {
    val song1 = song("You are my best friend", 4)
    val song2 = song("39", 5)
    val song3 = song("Bohemian Rhapsody", 11)
    val song4 = song("Sister Moonshine", 2)
    val song5 = song("A Soapbox opera", 4)
    val items = List((Album1, song1), (Album1, song2), (Album2, song4), (Album2, song5),
      (Album1, song3))

    val model = AlbumTableModel(items)
    model songsFor Album1 should contain inOrderOnly(song1, song2, song3)
    model songsFor Album2 should contain inOrderOnly(song4, song5)
  }

  it should "support adding songs in arbitrary order" in {
    val song1 = song("You are my best friend", 4)
    val song2 = song("39", 5)
    val song3 = song("Bohemian Rhapsody", 11)

    val model = appendSongs()(Album1, song2, song1, song3)
    model songsFor Album1 should contain inOrderOnly(song1, song2, song3)
  }

  it should "ignore songs in add() which are already contained" in {
    val song1 = song("The Prophet's Song", 8)
    val song2 = song(song1.getTitle(), 8)
    val model = appendSongs()(Album1, song1)

    model.add(Album2, song2) should be theSameInstanceAs model
  }
}
