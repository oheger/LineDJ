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

package de.oliver_heger.linedj.reorder.random

import de.oliver_heger.linedj.platform.model.SongData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec

object PlaylistReordererRandomSongsSpec {
  /** The number of test songs. */
  private val SongCount = 8

  /** A sequence with test songs. */
  private val TestSongs = createTestSongs()

  /**
    * Creates a ''SongData'' object with the specified title.
    *
    * @param name the song name
    * @return the created ''SongData''
    */
  private def createSong(name: String): SongData =
    SongData(MediumID.UndefinedMediumID, "song://" + name, MediaMetaData(title = Some(name)), null)

  /**
    * Creates a sequence with test songs.
    *
    * @return the sequence with songs
    */
  private def createTestSongs(): Seq[SongData] =
    1 to SongCount map (i => createSong("TestSong" + i))
}

/**
  * Test class for ''PlaylistReordererRandomSongs''.
  */
class PlaylistReordererRandomSongsSpec extends FlatSpec with Matchers {

  import PlaylistReordererRandomSongsSpec._

  /**
    * Invokes the passed in order with the test songs and checks basic
    * properties of the result.
    *
    * @param reorder the reorder object
    * @return the produced sequence of songs
    */
  private def reorderAndCheck(reorder: PlaylistReordererRandomSongs): Seq[SongData] = {
    val songs = reorder reorder TestSongs
    songs should contain theSameElementsAs TestSongs
    songs
  }

  "A PlaylistReordererRandomSongs" should "return a name" in {
    val reorder = new PlaylistReordererRandomSongs

    reorder.name should not be null
  }

  it should "produce random orders" in {
    val reorder = new PlaylistReordererRandomSongs

    @tailrec
    def check(attempts: Int): Boolean = {
      if (attempts <= 0) false
      else {
        val songs = reorderAndCheck(reorder)
        if (songs != TestSongs) true
        else check(attempts - 1)
      }
    }

    check(8) shouldBe true
  }
}
