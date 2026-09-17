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

package de.oliver_heger.linedj.apps.pleditor.reorder

import de.oliver_heger.linedj.apps.pleditor.SongData
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongDataExtractorSpec:
  /** The ID of a test media file. */
  private val TestFileID = "12345678901234567890"

  /** The URI of a test media file. */
  private val TestURI = "some/song/uri.mp3"

  /**
    * Creates a [[SongData]] object with the specified properties.
    *
    * @param title         the optional title
    * @param artist        the optional artist
    * @param album         the optional album
    * @param inceptionYear the optional inception year
    * @param trackNumber   the optional track number
    * @param withFileInfo  flag whether file information is available
    * @return the created [[SongData]]
    */
  private def createSong(title: Option[String] = None,
                         artist: Option[String] = None,
                         album: Option[String] = None,
                         inceptionYear: Option[Int] = None,
                         trackNumber: Option[Int] = None,
                         withFileInfo: Boolean = true): SongData =
    val metadata = MediaMetadata(
      title = title,
      artist = artist,
      album = album,
      inceptionYear = inceptionYear,
      trackNumber = trackNumber,
      size = 0,
      checksum = TestFileID
    )
    val optFileInfo =
      if withFileInfo then
        Some(ArchiveModel.MediaFileInfo(
          metadata = metadata,
          fileUri = MediaFileUri(TestURI),
          mediumID = Checksums.MediumChecksum("test-medium-id")
        ))
      else None
    SongData(TestFileID, optFileInfo)
end SongDataExtractorSpec

/**
  * Test class for [[SongDataExtractor]].
  */
class SongDataExtractorSpec extends AnyFlatSpec, Matchers:

  import SongDataExtractorSpec.*

  "A SongDataExtractor" should "return the title of a song" in :
    SongDataExtractor.title(createSong(title = Some("A title"))) should be(Some("A title"))

  it should "return None for a song without a title" in :
    SongDataExtractor.title(createSong()) should be(None)

  it should "return None for a song with an empty title" in :
    SongDataExtractor.title(createSong(title = Some(""))) should be(None)

  it should "return the artist of a song" in :
    SongDataExtractor.artist(createSong(artist = Some("An artist"))) should be(Some("An artist"))

  it should "return None for a song without an artist" in :
    SongDataExtractor.artist(createSong()) should be(None)

  it should "return the album of a song" in :
    SongDataExtractor.album(createSong(album = Some("An album"))) should be(Some("An album"))
  
  it should "return None for a song without an album" in :
    SongDataExtractor.album(createSong()) should be(None)

  it should "return the inception year of a song" in :
    SongDataExtractor.inceptionYear(createSong(inceptionYear = Some(1984))) should be(Some(1984))

  it should "return None for a song without an inception year" in :
    SongDataExtractor.inceptionYear(createSong()) should be(None)

  it should "return the track number of a song" in :
    SongDataExtractor.trackNumber(createSong(trackNumber = Some(3))) should be(Some(3))

  it should "return None for a song without a track number" in :
    SongDataExtractor.trackNumber(createSong()) should be(None)

  it should "return the URI of a song" in :
    SongDataExtractor.uri(createSong()) should be(TestURI)

  it should "return None for all properties if no file information is available" in :
    val song = createSong()

    SongDataExtractor.title(song) should be(None)
    SongDataExtractor.artist(song) should be(None)
    SongDataExtractor.album(song) should be(None)
    SongDataExtractor.inceptionYear(song) should be(None)
    SongDataExtractor.trackNumber(song) should be(None)

  it should "return an empty URI if no file information is available" in :
    SongDataExtractor.uri(createSong(withFileInfo = false)) should be("")
