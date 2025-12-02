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

package de.oliver_heger.linedj.archive.server.model

import de.oliver_heger.linedj.archive.server.model.ArchiveModel.MediaFileInfo
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*

/**
  * Test class for [[ArchiveModel]].
  */
class ArchiveModelSpec extends AnyFlatSpec with Matchers with ArchiveModel.ArchiveJsonSupport:
  /**
    * Implements a generic check for a JSON serialization round-trip. This
    * should verify whether correct JSON formats are in place for the type
    * under test.
    *
    * @param obj the object to be tested
    * @tparam T the type of this object
    */
  private def checkSerialization[T: JsonFormat](obj: T): Unit =
    val jsonAst = obj.toJson
    val json = jsonAst.prettyPrint

    val jsonAst2 = json.parseJson
    val obj2 = jsonAst2.convertTo[T]

    obj2 should be(obj)

  "JSON serialization" should "work for ArtistInfo" in :
    val artistInfo = ArchiveModel.ArtistInfo("artistID", "Name of the artist")

    checkSerialization(artistInfo)

  it should "work for AlbumInfo" in :
    val albumInfo = ArchiveModel.AlbumInfo("albumId", "Name of the album")

    checkSerialization(albumInfo)

  it should "work for MediaMetadata" in :
    val metadata = MediaMetadata(
      title = Some("Song title"),
      artist = Some("Artist"),
      album = Some("Album"),
      inceptionYear = Some(1984),
      trackNumber = Some(3),
      duration = Some(600),
      formatDescription = Some("192bps"),
      size = 54321,
      checksum = "1234567890"
    )

    checkSerialization(metadata)

  it should "work for ItemsResult" in :
    val items = (1 to 16).map: idx =>
      ArchiveModel.ArtistInfo(s"art$idx", "Test artist $idx")
    val itemsResult = ArchiveModel.ItemsResult(items.toList)

    checkSerialization(itemsResult)

  it should "work for MediaFileInfo" in :
    val metadata = MediaMetadata(
      title = Some("Song title"),
      artist = Some("Artist"),
      album = Some("Album"),
      inceptionYear = Some(1984),
      trackNumber = Some(3),
      duration = Some(600),
      formatDescription = Some("192bps"),
      size = 54321,
      checksum = "1234567890"
    )
    val uri = MediaFileUri("path/to/song/file.mp3")
    val mediumID = Checksums.MediumChecksum("some-medium-id")
    val fileInfo = MediaFileInfo(metadata, uri, mediumID)

    checkSerialization(fileInfo)

  it should "work for MediaFileDownloadInfo" in :
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/some/path/to/a/media-file.mp3"),
      archiveName = "SomeTestArchive"
    )

    checkSerialization(downloadInfo)
