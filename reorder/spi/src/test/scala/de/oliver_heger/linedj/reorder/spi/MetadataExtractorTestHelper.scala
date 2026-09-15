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

package de.oliver_heger.linedj.reorder.spi

/**
  * A test helper object providing functionality for creating
  * [[MetadataExtractor]] objects that operate on a test data class.
  */
object MetadataExtractorTestHelper:
  /**
    * A simple data class representing a media file that can be used by a
    * concrete [[MetadataExtractor]] implementation.
    *
    * @param title         the title of the media file
    * @param artist        the artist name
    * @param album         the album name
    * @param inceptionYear the inception year of the album
    * @param trackNumber   the track number
    * @param uri           the URI of the media file on its medium
    */
  case class TestMediaFile(title: Option[String],
                           artist: Option[String],
                           album: Option[String],
                           inceptionYear: Option[Int],
                           trackNumber: Option[Int],
                           uri: String)

  /**
    * A concrete [[MetadataExtractor]] implementation operating on
    * _TestMediaFile_ objects. It is used to test the methods defined by the
    * trait.
    */
  object TestMetadataExtractor extends MetadataExtractor[TestMediaFile]:
    override def title(data: TestMediaFile): Option[String] = data.title

    override def artist(data: TestMediaFile): Option[String] = data.artist

    override def album(data: TestMediaFile): Option[String] = data.album

    override def inceptionYear(data: TestMediaFile): Option[Int] = data.inceptionYear

    override def trackNumber(data: TestMediaFile): Option[Int] = data.trackNumber

    override def uri(data: TestMediaFile): String = data.uri

  /**
    * Creates a _TestMediaFile_ object with the specified properties. All
    * optional properties (the metadata) can be undefined by passing _None_.
    *
    * @param title         the title of the media file
    * @param artist        the artist name
    * @param album         the album name
    * @param inceptionYear the inception year of the album
    * @param trackNumber   the track number
    * @param uri           the URI of the media file
    * @return the created _TestMediaFile_
    */
  def createFile(title: Option[String] = None,
                 artist: Option[String] = None,
                 album: Option[String] = None,
                 inceptionYear: Option[Int] = None,
                 trackNumber: Option[Int] = None,
                 uri: String = "medium/test.mp3"): TestMediaFile =
    TestMediaFile(title, artist, album, inceptionYear, trackNumber, uri)
