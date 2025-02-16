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

package de.oliver_heger.linedj.reorder.album

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object PlaylistReordererAlbumSpec:
  /**
    * Creates a ''SongData'' object with the specified metadata.
    *
    * @param data the metadata for the song
    * @return the created ''SongData''
    */
  private def createSong(data: MediaMetadata): SongData =
    SongData(MediaFileID(MediumID.UndefinedMediumID, "song://" + data.title.getOrElse("testSong")),
      data, data.title getOrElse "", data.artist getOrElse "", data.album getOrElse "")
end PlaylistReordererAlbumSpec

/**
  * Test class for ''PlaylistReordererAlbum''.
  */
class PlaylistReordererAlbumSpec extends AnyFlatSpec with Matchers:

  import PlaylistReordererAlbumSpec.*

  "A PlaylistReordererAlbum" should "return a name" in:
    val reorder = new PlaylistReordererAlbum

    reorder.name should not be null

  it should "order a sequence of songs correctly" in:
    val s1 = createSong(MediaMetadata(inceptionYear = Some(1983), album = Some("Crisis"),
      trackNumber = Some(1)))
    val s2 = createSong(MediaMetadata(inceptionYear = Some(1983), album = Some("Crisis"),
      trackNumber = Some(2), title = Some("Moonlight Shadow")))
    val s3 = createSong(MediaMetadata(inceptionYear = Some(1984), album = Some("Ammonia Avenue"),
      artist = Some("Alan Parson"), title = Some("Some song")))
    val s4 = createSong(MediaMetadata(inceptionYear = Some(1984), album = Some("Discovery"),
      title = Some("Talk about your life"), artist = Some("Mike Oldfield")))
    val s5 = createSong(MediaMetadata(album = Some("On every street"), trackNumber = Some(1),
      title = Some("Calling Elvis")))
    val songs = List(s3, s1, s5, s4, s2)
    val reorder = new PlaylistReordererAlbum

    reorder reorder songs should be(List(s1, s2, s3, s4, s5))
