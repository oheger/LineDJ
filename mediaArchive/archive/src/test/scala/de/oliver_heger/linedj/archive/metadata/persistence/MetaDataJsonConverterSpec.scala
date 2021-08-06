/*
 * Copyright 2015-2021 The Developers Team.
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

import java.nio.file.{Path, Paths}

import de.oliver_heger.linedj.archivecommon.parser.MetaDataParser
import de.oliver_heger.linedj.io.parser.{JSONParser, ParserImpl}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData
import de.oliver_heger.linedj.shared.archive.union.MetaDataProcessingSuccess
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''MetaDataJsonConverter''.
  */
class MetaDataJsonConverterSpec extends AnyFlatSpec with Matchers {
  /**
    * Converts media meta data to a string using the converter and parses this
    * string again using the JSON parser.
    *
    * @param metaData the meta data
    * @param path     the path
    * @param uri      the URI
    * @return the parsed meta data processing result
    */
  private def convertAndParse(metaData: MediaMetaData, path: Path, uri: String):
  MetaDataProcessingSuccess = {
    val converter = new MetaDataJsonConverter
    val json = "[" + converter.convert(uri, path.toString, metaData) + "]"
    val parser = new MetaDataParser(ParserImpl, JSONParser.jsonParser(ParserImpl))
    val (results, optFailure) = parser.processChunk(json, MediumID("irrelevant", None), lastChunk
      = true, None)
    optFailure shouldBe empty
    results should have size 1
    results.head
  }

  "A MetaDataJsonConverter" should "produce a correct JSON representation" in {
    val metaData = MediaMetaData(title = Some("Title"), artist = Some("Artist"),
      album = Some("Album"), inceptionYear = Some(1988), trackNumber = Some(4),
      duration = Some(480), formatDescription = Some("mp3 128"), size = 20160323)
    val path = Paths get "someTestSong.mp3"
    val uri = "song://someTestSong.mp3"

    val parsedData = convertAndParse(metaData, path, uri)
    parsedData.path should be(path.toString)
    parsedData.uri should be(uri)
    parsedData.metaData should be(metaData)
  }

  it should "deal with optional meta data properties" in {
    val metaData = MediaMetaData(title = Some("Title"))
    val path = Paths get "someTestSong.mp3"
    val uri = "song://someTestSong.mp3"

    val parsedData = convertAndParse(metaData, path, uri)
    parsedData.metaData.size should be(0)
    parsedData.metaData.artist shouldBe empty
  }

  it should "quote quotation marks in strings" in {
    val metaData = MediaMetaData(title = Some("\"Title\""), artist = Some("\"Artist\""),
      album = Some("\"Album\""), inceptionYear = Some(1988), trackNumber = Some(4),
      duration = Some(480), formatDescription = Some("\"mp3 128\""), size = 20160323)
    val quotedData = metaData.copy(title = Some("'Title'"), artist = Some("'Artist'"),
      album = Some("'Album'"), formatDescription = Some("'mp3 128'"))
    val path = Paths get "someTestSong.mp3"
    val uri = "song://someTestSong.mp3"

    val parsedData = convertAndParse(metaData, path, uri)
    parsedData.metaData should be(quotedData)
  }
}
