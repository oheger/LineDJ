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

package de.oliver_heger.linedj.apps.player

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongDataSpec:
  /** Metadata with all optional properties defined. */
  private val TestMetadata = MediaMetadata(
    title = Some("Test Song"),
    artist = Some("Test Artist"),
    album = Some("Test Album"),
    inceptionYear = Some(2024),
    trackNumber = Some(7),
    duration = Some(215),
    formatDescription = Some("MP3"),
    size = 123456L,
    checksum = "test-checksum"
  )

  /** File information for the test song. */
  private val TestFileInfo = ArchiveModel.MediaFileInfo(
    metadata = TestMetadata,
    fileUri = MediaFileUri("test/song.mp3"),
    mediumID = Checksums.MediumChecksum("test-medium")
  )

  /** A test instance with complete properties. */
  private val TestSong = SongData(TestFileInfo)

  /**
    * Returns a [[SongData]] object with the given metadata. Other properties
    * are derived from the test data.
    *
    * @param data the updated metadata
    * @return the resulting [[SongData]]
    */
  private def songWithMetadata(data: MediaMetadata): SongData =
    SongData(TestFileInfo.copy(metadata = data))
end SongDataSpec

/**
  * Test class for [[SongData]].
  */
class SongDataSpec extends AnyFlatSpec, Matchers:

  import SongDataSpec.*

  "A SongData" should "return the title of the song" in :
    TestSong.getTitle should be("Test Song")

  it should "return a special value for the title if it is undefined" in :
    val songData = songWithMetadata(TestMetadata.copy(title = None))

    songData.getTitle should be("-")

  it should "return the artist of the song" in :
    TestSong.getArtist should be("Test Artist")

  it should "return a special value for the artist if it is undefined" in :
    val songData = songWithMetadata(TestMetadata.copy(artist = None))

    songData.getArtist should be("-")

  it should "return the album of the song" in :
    TestSong.getAlbum should be("Test Album")

  it should "return a special value for the album if it is undefined" in :
    val songData = songWithMetadata(TestMetadata.copy(album = None))

    songData.getAlbum should be("-")

  it should "return the track number of the song" in :
    TestSong.getTrackNumber should be("7")

  it should "return a special value for the track number if it is undefined" in :
    val songData = songWithMetadata(TestMetadata.copy(trackNumber = None))

    songData.getTrackNumber should be("-")

  it should "return the inception year of the song" in :
    TestSong.getInceptionYear should be("2024")

  it should "return a special value for the inception year if it is undefined" in :
    val songData = songWithMetadata(TestMetadata.copy(inceptionYear = None))

    songData.getInceptionYear should be("-")

  it should "return the duration of the song" in :
    TestSong.getDuration should be(215)

  it should "return a special value for the duration if it is undefined" in :
    val songData = songWithMetadata(TestMetadata.copy(duration = None))

    songData.getDuration should be < 0
