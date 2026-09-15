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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''MetadataExtractor''.
  */
class MetadataExtractorSpec extends AnyFlatSpec with Matchers:

  import MetadataExtractorTestHelper.*

  "A MetadataExtractor" should "return the title of a data object if it is defined" in :
    TestMetadataExtractor.titleOrDefault(createFile(title = Some("A title"))) should be("A title")

  it should "return an empty string as title for a data object without a title" in :
    TestMetadataExtractor.titleOrDefault(createFile(title = None)) should be("")

  it should "return the artist of a data object if it is defined" in :
    TestMetadataExtractor.artistOrDefault(createFile(artist = Some("An artist"))) should be("An artist")

  it should "return an empty string as artist for a data object without an artist" in :
    TestMetadataExtractor.artistOrDefault(createFile(artist = None)) should be("")

  it should "return the album of a data object if it is defined" in :
    TestMetadataExtractor.albumOrDefault(createFile(album = Some("An album"))) should be("An album")

  it should "return an empty string as album for a data object without an album" in :
    TestMetadataExtractor.albumOrDefault(createFile(album = None)) should be("")

  it should "return the inception year of a data object if it is defined" in :
    TestMetadataExtractor.inceptionYearOrDefault(createFile(inceptionYear = Some(1984))) should be(1984)

  it should "return the default inception year for a data object without a year" in :
    TestMetadataExtractor.inceptionYearOrDefault(createFile(inceptionYear = None)) should be(0)

  it should "return the track number of a data object if it is defined" in :
    TestMetadataExtractor.trackNumberOrDefault(createFile(trackNumber = Some(7))) should be(7)

  it should "return the default track number for a data object without a track number" in :
    TestMetadataExtractor.trackNumberOrDefault(createFile(trackNumber = None)) should be(-1)
 