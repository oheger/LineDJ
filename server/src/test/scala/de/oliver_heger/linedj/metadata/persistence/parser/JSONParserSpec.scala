/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.metadata.persistence.parser

import de.oliver_heger.linedj.metadata.persistence.parser.JSONParser.JSONData
import de.oliver_heger.linedj.metadata.persistence.parser.ParserTypes.{Failure, Success}
import org.scalatest.{FlatSpec, Matchers}

object JSONParserSpec {
  /** A test JSON text. */
  private val JsonText =
    """
[
  {
  "uri" : "song://testMusic/song1.mp3",
  "title"  : "Roll over Beethoven",
  "artist" : "ELO"
  }
  ,
  {
  "uri"    : "song://testMusic/song2.mp3",
  "title"  : "Sultans of Swing",
  "artist" : "Dire Straits",
  "trackNo": 6,
  "year"   : 1978
  }
]
    """

  /** The JSON parser used within the tests. */
  private val jsonParser = JSONParser.jsonParser(ParserImpl)

  /**
    * Performs a test of chunk parsing. The test JSON string is split at the
    * specified position, and both chunks are parsed. The result is returned.
    *
    * @param split the position at which to split the test text
    * @return the result of the parse operation
    */
  private def chunkTest(split: Int): ParserTypes.Result[JSONData] = {
    val input1 = JsonText.substring(0, split)
    val input2 = JsonText.substring(split)
    ParserImpl.runChunk(jsonParser)(input1, lastChunk = false) match {
      case f@Failure(err, com, data) =>
        ParserImpl.runChunk(jsonParser)(input2, lastChunk = true, optFailure = Some(f))
      case s => s
    }
  }

}

/**
  * Test class for the JSON parser.
  */
class JSONParserSpec extends FlatSpec with Matchers {

  import JSONParserSpec._

  "A JSONParser" should "parse a JSON string" in {
    val result = ParserImpl.runChunk(jsonParser)(JsonText, lastChunk = true)

    result shouldBe a[Success[JSONData]]
    val songList = result.asInstanceOf[Success[JSONData]].get
    songList should have size 2
    val song1 = songList.head
    song1("uri") should be("song://testMusic/song1.mp3")
    song1("title") should be("Roll over Beethoven")
    song1("artist") should be("ELO")

    val song2 = songList(1)
    song2("uri") should be("song://testMusic/song2.mp3")
    song2("title") should be("Sultans of Swing")
    song2("artist") should be("Dire Straits")
    song2("trackNo") should be("6")
    song2("year") should be("1978")
  }

  it should "support parsing chunks" in {
    val result = ParserImpl.runChunk(jsonParser)(JsonText, lastChunk = true)
      .asInstanceOf[Success[JSONData]]
    val results = (1 until JsonText.length).map(chunkTest)
    val errors = results.zipWithIndex.filter(_._1.isInstanceOf[Failure])
    errors shouldBe 'empty
    results.filter(_.asInstanceOf[Success[JSONData]].get != result.get) should have size 0
  }

  it should "support parsing multiple chunks" in {
    val input1 = JsonText.substring(0, 18)
    val input2 = JsonText.substring(18, 124)
    val input3 = JsonText.substring(124)

    val result1 = ParserImpl.runChunk(jsonParser)(input1, lastChunk = false).asInstanceOf[Failure]
    val result2 = ParserImpl.runChunk(jsonParser)(input2,
      optFailure = Some(result1), lastChunk = false).asInstanceOf[Failure]
    val res3 = ParserImpl.runChunk(jsonParser)(input3,
      optFailure = Some(result2), lastChunk = true)
    val result3 = res3.asInstanceOf[Success[JSONData]]
    val resultSingle = ParserImpl.runChunk(jsonParser)(JsonText,
      lastChunk = true).asInstanceOf[Success[JSONData]]
    result3.get should be(resultSingle.get)
  }
}
