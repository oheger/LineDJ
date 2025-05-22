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

package de.oliver_heger.linedj.archive.metadata.persistence

import de.oliver_heger.linedj.archive.metadata.persistence.MetadataJsonConverterSpec.ValidProperties
import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*

object MetadataJsonConverterSpec:
  /** A set listing the names of properties allowed for a metadata file. */
  private val ValidProperties = Set("album", "artist", "title", "uri", "size", "formatDescription",
    "duration", "trackNumber", "inceptionYear", "checksum")

/**
  * Test class for ''MetaDataJsonConverter''.
  */
class MetadataJsonConverterSpec extends AnyFlatSpec with Matchers:
  /**
    * Converts media metadata to a string using the converter and parses this
    * string again.
    *
    * @param metaData the metadata
    * @param uri      the URI
    * @return the parsed metadata processing result
    */
  private def convertAndParse(metaData: MediaMetadata, uri: String): MetadataParser.MetadataWithUri =
    val converter = new MetadataJsonConverter
    val json = converter.convert(uri, metaData)

    val jsonAst = json.parseJson.asJsObject
    forEvery(jsonAst.fields.keySet) { field => ValidProperties should contain (field) }
    jsonAst.convertTo[MetadataParser.MetadataWithUri]

  "A MetadataJsonConverter" should "produce a correct JSON representation" in:
    val metadata = MediaMetadata(
      title = Some("Title"),
      artist = Some("Artist"),
      album = Some("Album"),
      inceptionYear = Some(1988),
      trackNumber = Some(4),
      duration = Some(480),
      formatDescription = Some("mp3 128"),
      size = 20160323,
      checksum = "checked"
    )
    val uri = "song://someTestSong.mp3"

    val parsedData = convertAndParse(metadata, uri)
    parsedData.uri should be(uri)
    parsedData.metadata should be(metadata)

  it should "deal with optional metadata properties" in:
    val metadata = MediaMetadata.UndefinedMediaData.copy(title = Some("Title"))
    val uri = "song://someTestSong.mp3"

    val parsedData = convertAndParse(metadata, uri)
    parsedData.metadata.size should be(MediaMetadata.UndefinedMediaData.size)
    parsedData.metadata.artist shouldBe empty

  it should "handle quotation marks in strings" in:
    val metadata = MediaMetadata(
      title = Some("\"Title\""),
      artist = Some("\"Artist\""),
      album = Some("\"Album\""),
      inceptionYear = Some(1988),
      trackNumber = Some(4),
      duration = Some(480),
      formatDescription = Some("\"mp3 128\""),
      size = 20160323,
      checksum = "1234567890"
    )
    val uri = "song://someTestSong.mp3"

    val parsedData = convertAndParse(metadata, uri)
    parsedData.metadata should be(metadata)
