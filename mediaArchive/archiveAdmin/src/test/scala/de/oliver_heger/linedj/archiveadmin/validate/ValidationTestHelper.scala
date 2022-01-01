/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.archiveadmin.validate

import de.oliver_heger.linedj.archiveadmin.validate.MetaDataValidator.{MediaAlbum, MediaFile}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediumID, MediumInfo}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * An object implementing common functionality required by tests of the
  * validation facilities.
  *
  * This object mainly provides functions to generate test data.
  */
object ValidationTestHelper {
  /** A prefix for the generation of URIs for media. */
  private val MediumUriPrefix = "testMedium"

  /** A test medium ID. */
  val Medium: MediumID = testMedium(1)

  /**
    * Generates a test medium ID based on the given index.
    *
    * @param idx the index
    * @return the test medium ID
    */
  def testMedium(idx: Int): MediumID =
    MediumID(MediumUriPrefix + idx, Some(s"testMedium$idx.settings"))

  /**
    * Returns the index of a test medium that was generated using
    * ''testMedium()''.
    *
    * @param mid the medium ID
    * @return the index of this test medium
    */
  def extractMediumIndex(mid: MediumID): Int =
    mid.mediumURI.substring(MediumUriPrefix.length).toInt

  /**
    * Generates a test medium info object based on the given index.
    *
    * @param idx the index
    * @return the test medium info
    */
  def testMediumInfo(idx: Int): MediumInfo = {
    val mid = testMedium(idx)
    MediumInfo("Medium " + mid.mediumURI, "desc" + idx, mid, "order" + idx, "params" + idx, "check" + idx)
  }

  /**
    * Generates an object with available media that contains the given number
    * of test media.
    *
    * @param count the number of test media
    * @return the available media
    */
  def createAvailableMedia(count: Int): AvailableMedia = {
    val mediaMap = (1 to count).map(i => (testMedium(i), testMediumInfo(i))).toMap
    AvailableMedia(mediaMap.toList)
  }

  /**
    * Generates the URI of a test media file based on the given indices.
    *
    * @param idx      the index of the file
    * @param albumIdx the index of the album
    * @return the test URI for this media file
    */
  def fileUri(idx: Int, albumIdx: Int = 1, mediumIdx: Int = 1): String =
    s"${albumUri(albumIdx, mediumIdx)}/testMediaFile$idx.mp3"

  /**
    * Generates the name of a test album.
    *
    * @param idx the index
    * @return the name of this test album
    */
  def albumName(idx: Int): String = "testAlbum" + idx

  /**
    * Generates the URI of a test album based on the given index.
    *
    * @param idx       the index
    * @param mediumIdx the index of the medium
    * @return the URI for this test album
    */
  def albumUri(idx: Int, mediumIdx: Int = 1): String = testMedium(mediumIdx).mediumURI + "/" + albumName(idx)

  /**
    * Generates test meta data based on the given indices. The meta data is
    * fully defined.
    *
    * @param songIdx  the index of the song
    * @param albumIdx the index of the album
    * @return test meta data with this index
    */
  def metaData(songIdx: Int, albumIdx: Int = 1): MediaMetaData =
    MediaMetaData(title = Some("Song" + songIdx), artist = Some("artist"), album = Some("album" + albumIdx),
      inceptionYear = Some(2018 + albumIdx), trackNumber = Some(songIdx), duration = Some(60 + songIdx),
      size = 1000 + songIdx)

  /**
    * Generates a test media file based on the given indices.
    *
    * @param idx       the index of the song
    * @param albumIdx  the index of the album
    * @param mediumIdx the index of the medium
    * @return the test file
    */
  def file(idx: Int, albumIdx: Int = 1, mediumIdx: Int = 1): MediaFile =
    MediaFile(testMedium(mediumIdx), fileUri(idx, albumIdx, mediumIdx), metaData(idx, albumIdx))

  /**
    * Generates a test album with media files. The album contains the given
    * number of songs with valid meta data plus songs with the given additional
    * meta data (which may be inconsistent).
    *
    * @param idx            the index of the test album
    * @param numberOfSongs  the number of songs on this album
    * @param additionalData meta data for additional songs
    * @return the test album
    */
  def album(idx: Int, numberOfSongs: Int, additionalData: MediaMetaData*): MediaAlbum = {
    val songData = (1 to numberOfSongs) map (metaData(_, idx))
    MediaAlbum(Medium, "album" + idx, additionalData.toList ::: songData.toList)
  }

  /**
    * Generates a list with the files of an album matching the criteria
    * specified.
    *
    * @param albumIdx      the index of the test album
    * @param numberOfSongs the number of songs on this album
    * @param mediumIdx     the index of the medium
    * @return a list with the files on this album
    */
  def albumFiles(albumIdx: Int, numberOfSongs: Int, mediumIdx: Int = 1): List[MediaFile] =
    (1 to numberOfSongs).map(file(_, albumIdx, mediumIdx)).toList
}
