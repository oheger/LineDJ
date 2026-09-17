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

package de.oliver_heger.linedj.apps.pleditor

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongDataSpec:
  /** The ID of a test media file. */
  private val TestFileID = "12345678901234567890"

  /** An info object with fully defined properties. */
  private val DefinedFileInfo = ArchiveModel.MediaFileInfo(
    metadata = MediaMetadata(
      title = Some("Money for Nothing"),
      artist = Some("Dire Straits"),
      album = Some("Brothers in Arms"),
      inceptionYear = Some(1985),
      duration = Some(40000),
      size = 16384,
      checksum = TestFileID
    ),
    fileUri = MediaFileUri("/direStraits/brothersInArms/07 Money for Nothing.mp3"),
    mediumID = Checksums.MediumChecksum("test-medium-id")
  )

  /** An info object with metadata that are mostly undefined. */
  private val FileInfoNoMetadata = ArchiveModel.MediaFileInfo(
    metadata = MediaMetadata(size = 1024, checksum = TestFileID),
    fileUri = MediaFileUri("/direStraits/brothersInArms/07 Money for Nothing.mp3"),
    mediumID = Checksums.MediumChecksum("test-medium-id")
  )
end SongDataSpec

/**
  * Test class for [[SongData]].
  */
class SongDataSpec extends AnyFlatSpec, Matchers:

  import SongDataSpec.*

  "A SongData" should "return the title if it is defined" in :
    val data = SongData(TestFileID, Some(DefinedFileInfo))

    val title = data.getTitle

    title should be("Money for Nothing")

  it should "return a part of the ID if no metadata is available" in :
    val data = SongData(TestFileID, None)

    val title = data.getTitle

    title should be("1234567890123456")

  it should "return a part of the URI if the title in the metadata is undefined" in :
    val data = SongData(TestFileID, Some(FileInfoNoMetadata))

    val title = data.getTitle

    title should be("07 Money for Nothing.mp3")

  it should "return a part of the URI if the title in the metadata is empty" in :
    val metadata = DefinedFileInfo.metadata.copy(title = Some(""))
    val fileInfo = DefinedFileInfo.copy(metadata = metadata)
    val data = SongData(TestFileID, Some(fileInfo))

    val title = data.getTitle

    title should be("07 Money for Nothing.mp3")

  it should "return the full URI if the title in the metadata is undefined and the URI has no slash" in :
    val data = SongData(TestFileID, Some(FileInfoNoMetadata.copy(fileUri = MediaFileUri("test.mp3"))))

    val title = data.getTitle

    title should be("test.mp3")

  it should "return an empty artist if no metadata is available" in :
    val data = SongData(TestFileID, None)

    val artist = data.getArtist

    artist should be("")

  it should "return an empty artist if no artist is defined in the metadata" in :
    val data = SongData(TestFileID, Some(FileInfoNoMetadata))

    val artist = data.getArtist

    artist should be("")

  it should "return the artist if it is defined" in :
    val data = SongData(TestFileID, Some(DefinedFileInfo))

    val artist = data.getArtist

    artist should be("Dire Straits")

  it should "return an empty album if no metadata is available" in :
    val data = SongData(TestFileID, None)

    val album = data.getAlbum

    album should be("")

  it should "return an empty album if no album is defined in the metadata" in :
    val data = SongData(TestFileID, Some(FileInfoNoMetadata))

    val album = data.getAlbum

    album should be("")

  it should "return the album if it is defined" in :
    val data = SongData(TestFileID, Some(DefinedFileInfo))

    val album = data.getAlbum

    album should be("Brothers in Arms")

  it should "return a negative duration if no metadata is available" in :
    val data = SongData(TestFileID, None)

    val duration = data.getDuration

    duration should be < 0

  it should "return a negative duration if no duration is defined in the metadata" in :
    val data = SongData(TestFileID, Some(FileInfoNoMetadata))

    val duration = data.getDuration

    duration should be < 0

  it should "return the duration if it is defined" in :
    val data = SongData(TestFileID, Some(DefinedFileInfo))

    val duration = data.getDuration

    duration should be(40000)
