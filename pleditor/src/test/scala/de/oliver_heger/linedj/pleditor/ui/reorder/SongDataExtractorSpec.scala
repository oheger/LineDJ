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

package de.oliver_heger.linedj.pleditor.ui.reorder

import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object SongDataExtractorSpec:
  /**
    * Creates a [[SongData]] object with the specified properties. Properties
    * that are not defined are mapped to empty strings in the explicit song
    * data fields.
    *
    * @param title         the optional title
    * @param artist        the optional artist
    * @param album         the optional album
    * @param inceptionYear the optional inception year
    * @param trackNumber   the optional track number
    * @return the created [[SongData]]
    */
  private def createSong(title: Option[String] = None,
                         artist: Option[String] = None,
                         album: Option[String] = None,
                         inceptionYear: Option[Int] = None,
                         trackNumber: Option[Int] = None): SongData =
    SongData(
      MediaFileID(MediumID.UndefinedMediumID, "song://uri"),
      MediaMetadata(
        title = title,
        artist = artist,
        album = album,
        inceptionYear = inceptionYear,
        trackNumber = trackNumber,
        size = 0,
        checksum = ""
      ),
      title getOrElse "",
      artist getOrElse "",
      album getOrElse ""
    )
end SongDataExtractorSpec

/**
  * Test class for [[SongDataExtractor]].
  */
class SongDataExtractorSpec extends AnyFlatSpec with Matchers:

  import SongDataExtractorSpec.*

  "SongDataExtractor" should "return the title of a song" in :
    SongDataExtractor.title(createSong(title = Some("A title"))) should be(Some("A title"))

  it should "return None for a song without a title" in :
    SongDataExtractor.title(createSong()) should be(None)

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
    val uri = "some/song/uri.mp3"
    val song = SongData(MediaFileID(MediumID.UndefinedMediumID, uri),
      MediaMetadata.UndefinedMediaData, "", "", "")

    SongDataExtractor.uri(song) should be(uri)
