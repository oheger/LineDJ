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

import de.oliver_heger.linedj.media.MediumID
import de.oliver_heger.linedj.metadata.MetaDataProcessingResult
import de.oliver_heger.linedj.metadata.persistence.parser.JSONParser.JSONData
import de.oliver_heger.linedj.metadata.persistence.parser.ParserTypes.{Result, Failure}
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object MetaDataParserSpec {
  /** A test medium ID. */
  private val TestMedium = MediumID("someTestMedium", Some("test"))

  /**
    * Generates text for a test chunk of data. This data does not really have a
    * meaning.
    *
    * @param index an index to make the text unique
    * @return the generated text
    */
  private def generateChunk(index: Int): String =
    s"Content for chunk $index!"

  /**
    * Prepares the mock objects associated with the test parser to return a
    * success result with the given content.
    *
    * @param parser     the test parser
    * @param chunkIndex the index of the chunk to be passed to the parser
    * @param results    the results to be returned
    * @return the test parser
    */
  private def expectSuccessResult(parser: MetaDataParser, chunkIndex: Int, results: JSONParser
  .JSONData): MetaDataParser = {
    expectParserRun(parser, chunkIndex).thenReturn(ParserTypes.Success(results, 1))
    parser
  }

  /**
    * Expects an invocation of the chunk parser with the given parameters.
    *
    * @param parser     the test parser
    * @param chunkIndex the chunk index
    * @param lastChunk  the flag for the last chunk
    * @param optFailure an optional failure
    * @return an object to define mock behavior
    */
  private def expectParserRun(parser: MetaDataParser, chunkIndex: Int, lastChunk: Boolean = false,
                              optFailure: Option[Failure] = None):
  OngoingStubbing[Result[JSONData]] = {
    when(parser.chunkParser.runChunk(parser.jsonParser)(generateChunk(chunkIndex),
      lastChunk, optFailure))
  }

  /**
    * Creates a number of result objects that can be returned by the mock
    * parser. Only a single result object is valid and contains all
    * required properties.
    *
    * @return the sequence with result objects
    */
  private def createResultObjects(): Vector[Map[String, String]] = {
    val props1 = Map(MetaDataParser.PropPath -> "path1",
      MetaDataParser.PropTitle -> "Title1")
    val props2 = Map(MetaDataParser.PropArtist -> "Artist",
      MetaDataParser.PropUri -> "song://uri2.mp3")
    val props3 = Map(MetaDataParser.PropPath -> "path3",
      MetaDataParser.PropTitle -> "Title",
      MetaDataParser.PropUri -> "song://uri3.mp3")
    Vector(props1, props2, props3)
  }
}

/**
  * Test class for ''MetaDataParser''.
  */
class MetaDataParserSpec extends FlatSpec with Matchers with MockitoSugar {

  import MetaDataParserSpec._

  /**
    * Creates a mock for chunk parser.
    *
    * @return the chunk parser mock
    */
  private def createChunkParserMock(): ChunkParser[ParserTypes.Parser, ParserTypes.Result,
    Failure] =
    mock[JsonChunkParser]

  /**
    * Creates a test parser that uses a mock chunk parser.
    *
    * @return the test parser
    */
  private def createParser(): MetaDataParser =
    new MetaDataParser(createChunkParserMock(), mock[ParserTypes.Parser[JSONParser.JSONData]])

  /**
    * Invokes the test parser and assumes that a single result is returned.
    * This is the result of this function.
    *
    * @param p the test parser
    * @return the result returned by the parser
    */
  private def fetchSingleParseResult(p: MetaDataParser): MetaDataProcessingResult = {
    val (data, failure) = p.processChunk(generateChunk(1), TestMedium, lastChunk = false,
      optFailure = None)
    failure shouldBe 'empty
    data should have size 1
    data.head
  }

  "A MetaDataParser" should "convert a JSON map to a MediaMetaData object" in {
    val props = Map(MetaDataParser.PropAlbum -> "Album",
      MetaDataParser.PropArtist -> "Artist",
      MetaDataParser.PropDuration -> "180",
      MetaDataParser.PropFormatDescription -> "format",
      MetaDataParser.PropInceptionYear -> "2012",
      MetaDataParser.PropPath -> "path",
      MetaDataParser.PropSize -> "20160303212850",
      MetaDataParser.PropTitle -> "Title",
      MetaDataParser.PropTrackNumber -> "15",
      MetaDataParser.PropUri -> "song://uri.mp3")

    val result = fetchSingleParseResult(expectSuccessResult(createParser(), 1, Vector(props)))
    result.mediumID should be(TestMedium)
    result.path.toString should be(props(MetaDataParser.PropPath))
    result.uri should be(props(MetaDataParser.PropUri))
    result.metaData.album.get should be("Album")
    result.metaData.artist.get should be("Artist")
    result.metaData.duration.get should be(180)
    result.metaData.formatDescription.get should be("format")
    result.metaData.inceptionYear.get should be(2012)
    result.metaData.size should be(20160303212850L)
    result.metaData.title.get should be("Title")
    result.metaData.trackNumber.get should be(15)
  }

  it should "handle invalid Int properties correctly" in {
    val props = Map(MetaDataParser.PropDuration -> "> 180",
      MetaDataParser.PropInceptionYear -> "MCMLXXXIV",
      MetaDataParser.PropPath -> "path",
      MetaDataParser.PropSize -> "20160303213928",
      MetaDataParser.PropTitle -> "Title",
      MetaDataParser.PropTrackNumber -> "unknown",
      MetaDataParser.PropUri -> "song://uri.mp3")

    val result = fetchSingleParseResult(expectSuccessResult(createParser(), 1, Vector(props)))
    result.metaData.duration shouldBe 'empty
    result.metaData.inceptionYear shouldBe 'empty
    result.metaData.trackNumber shouldBe 'empty
  }

  it should "handle an invalid size property" in {
    val props = Map(MetaDataParser.PropPath -> "path",
      MetaDataParser.PropSize -> "large",
      MetaDataParser.PropTitle -> "Title",
      MetaDataParser.PropUri -> "song://uri.mp3")

    val result = fetchSingleParseResult(expectSuccessResult(createParser(), 1, Vector(props)))
    result.metaData.size should be(-1)
  }

  it should "handle a missing size correctly" in {
    val props = Map(MetaDataParser.PropPath -> "path",
      MetaDataParser.PropTitle -> "Title",
      MetaDataParser.PropUri -> "song://uri.mp3")

    val result = fetchSingleParseResult(expectSuccessResult(createParser(), 1, Vector(props)))
    result.metaData.size should be(-1)
  }

  it should "return multiple results if available" in {
    val props1 = Map(MetaDataParser.PropPath -> "path1",
      MetaDataParser.PropTitle -> "Title1",
      MetaDataParser.PropUri -> "song://uri1.mp3")
    val props2 = Map(MetaDataParser.PropPath -> "path2",
      MetaDataParser.PropArtist -> "Artist",
      MetaDataParser.PropUri -> "song://uri2.mp3")

    val p = expectSuccessResult(createParser(), 2, Vector(props1, props2))
    val (data, failure) = p.processChunk(generateChunk(2), TestMedium, lastChunk = false,
      optFailure = None)
    failure shouldBe 'empty
    data should have size 2
    data.head.metaData.title.get should be("Title1")
    data(1).metaData.title shouldBe 'empty
    data(1).uri should be("song://uri2.mp3")
  }

  it should "filter out results that have no path or URI" in {
    val objects = createResultObjects()

    val result = fetchSingleParseResult(expectSuccessResult(createParser(), 1,
      objects))
    result.metaData.title.get should be("Title")
  }

  it should "handle a Failure without partial results" in {
    val failureIn = Some(Failure(ParseError(Nil), isCommitted = false, Nil))
    val failureOut = Some(Failure(ParseError(), isCommitted = true, Nil))
    val parser = createParser()
    expectParserRun(parser, 2, lastChunk = true, optFailure = failureIn).thenReturn(failureOut.get)

    val (data, optFailure) = parser.processChunk(generateChunk(2), TestMedium, lastChunk = true,
      failureIn)
    data should have size 0
    optFailure should be(failureOut)
  }

  it should "extract results from the partial data of a Failure result" in {
    val partialData = List("foo", "bar",
      ParserImpl.ManyPartialData[Map[String, String]](createResultObjects().toList), "other")
    val failureOut = Failure(ParseError(), isCommitted = true, partialData)
    val parser = createParser()
    expectParserRun(parser, 1, lastChunk = false).thenReturn(failureOut)

    val (data, optFailure) = parser.processChunk(generateChunk(1), TestMedium, lastChunk = false,
      None)
    data should have size 1
    data.head.metaData.title.get should be("Title")
    optFailure.get should be(failureOut.copy(partialData = List("foo", "bar", ParserImpl
      .ManyPartialData(Nil), "other")))
  }
}

/**
  * A specialized chunk parser type which makes handling the complex type
  * parameters easier.
  */
trait JsonChunkParser extends ChunkParser[ParserTypes.Parser, ParserTypes.Result, Failure]
