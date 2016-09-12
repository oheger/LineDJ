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

package de.oliver_heger.linedj.reorder.album

import de.oliver_heger.linedj.client.model.SongData
import de.oliver_heger.linedj.archive.media.MediumID
import de.oliver_heger.linedj.archive.metadata.MediaMetaData
import org.scalatest.{FlatSpec, Matchers}

object PlaylistReordererAlbumSpec {
  /**
    * Creates a ''SongData'' object with the specified meta data.
    *
    * @param data the meta data for the song
    * @return the created ''SongData''
    */
  private def createSong(data: MediaMetaData): SongData =
    SongData(MediumID.UndefinedMediumID, "song://" + data.title.getOrElse("testSong"), data, null)
}

/**
  * Test class for ''PlaylistReordererAlbum''.
  */
class PlaylistReordererAlbumSpec extends FlatSpec with Matchers {

  import PlaylistReordererAlbumSpec._

  "A PlaylistReordererAlbum" should "return a name" in {
    val reorder = new PlaylistReordererAlbum

    reorder.name should not be null
  }

  it should "order a sequence of songs correctly" in {
    val s1 = createSong(MediaMetaData(inceptionYear = Some(1983), album = Some("Crisis"),
      trackNumber = Some(1)))
    val s2 = createSong(MediaMetaData(inceptionYear = Some(1983), album = Some("Crisis"),
      trackNumber = Some(2), title = Some("Moonlight Shadow")))
    val s3 = createSong(MediaMetaData(inceptionYear = Some(1984), album = Some("Ammonia Avenue"),
      artist = Some("Alan Parson"), title = Some("Some song")))
    val s4 = createSong(MediaMetaData(inceptionYear = Some(1984), album = Some("Discovery"),
      title = Some("Talk about your life"), artist = Some("Mike Oldfield")))
    val s5 = createSong(MediaMetaData(album = Some("On every street"), trackNumber = Some(1),
      title = Some("Calling Elvis")))
    val songs = List(s3, s1, s5, s4, s2)
    val reorder = new PlaylistReordererAlbum

    reorder reorder songs should be(List(s1, s2, s3, s4, s5))
  }
}
