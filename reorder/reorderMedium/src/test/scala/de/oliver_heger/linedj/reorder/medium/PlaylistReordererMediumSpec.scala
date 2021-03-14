/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.reorder.medium

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object PlaylistReordererMediumSpec {
  /**
    * Creates a ''SongData'' object with the specified URI.
    * @param uri the URI
    * @return the created ''SongData''
    */
  private def createSong(uri: String): SongData =
    SongData(MediaFileID(MediumID.UndefinedMediumID, uri), MediaMetaData(), null, null, null)
}

/**
  * Test class for ''PlaylistReordererMedium''.
  */
class PlaylistReordererMediumSpec extends AnyFlatSpec with Matchers {
  import PlaylistReordererMediumSpec._

  "A PlaylistReordererMedium" should "return a name" in {
    val reorder = new PlaylistReordererMedium

    reorder.name should not be null
  }

  it should "order songs by their URI" in {
    val songA = createSong("A")
    val songB = createSong("B")
    val songC = createSong("C")
    val songD = createSong("D")
    val songs = List(songB, songD, songA, songC)
    val orderedSongs = List(songA, songB, songC, songD)
    val reorder = new PlaylistReordererMedium

    reorder reorder songs should be(orderedSongs)
  }
}
