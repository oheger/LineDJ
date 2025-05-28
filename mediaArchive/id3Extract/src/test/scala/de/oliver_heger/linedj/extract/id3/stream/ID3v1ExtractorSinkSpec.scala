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

package de.oliver_heger.linedj.extract.id3.stream

import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}

import java.nio.file.Paths
import scala.concurrent.Future

/**
  * Test class for [[ID3v1ExtractorSink]].
  */
class ID3v1ExtractorSinkSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues:
  def this() = this(ActorSystem("ID3v1ExtractorSinkSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Runs a stream with a test sink and returns the result produced by the
    * sink.
    *
    * @param source the source of the stream
    * @return the result produced by the test sink
    */
  private def runStream(source: Source[ByteString, Any]): Future[Option[MetadataProvider]] =
    val testSink = new ID3v1ExtractorSink
    source.runWith(testSink)

  /**
    * Runs a stream with a test sink over the content of the given test file.
    * The file is read with the given chunk size, so that also odd chunk sizes
    * can be tested. Returns the result produced by the sink.
    *
    * @param testFileName the name of the test file to process
    * @return the result produced by the test sink
    */
  private def processFile(testFileName: String, chunkSize: Int = 1024): Future[Option[MetadataProvider]] =
    val testFilePath = Option(getClass.getResource(s"/$testFileName"))
      .map(url => Paths.get(url.toURI))
    val source = FileIO.fromPath(testFilePath.value, chunkSize)
    runStream(source)

  "ID3v1ExtractorSink" should "handle a file without ID3v1 data" in :
    processFile("testID3v2Data.bin") map { result =>
      result shouldBe empty
    }

  it should "handle an empty stream" in :
    runStream(Source.empty) map { result =>
      result shouldBe empty
    }

  /**
    * Reads a file that contains ID3v1 metadata with the given chunk size and
    * tests whether the expected tags could be extracted.
    *
    * @param chunkSize the chunk size
    * @return the test result
    */
  private def checkExtractedID3v1Frame(chunkSize: Int): Future[Assertion] =
    processFile("testMP3id3v1.mp3", chunkSize) map { result =>
      val provider = result.value

      provider.title should be(Some("Test Title"))
      provider.artist should be(Some("Test Artist"))
      provider.album should be(Some("Test Album"))
    }

  it should "extract valid ID3v1 data with a standard chunk size" in :
    checkExtractedID3v1Frame(8192)

  it should "extract valid ID3v1 data with a large chunk size" in :
    checkExtractedID3v1Frame(65536)

  it should "extract valid ID3v1 data with a small chunk size" in :
    checkExtractedID3v1Frame(32)

  it should "extract valid ID3v1 data with an odd chunk size" in :
    checkExtractedID3v1Frame(127)

  it should "extract valid ID3v1 data with a small odd chunk size" in :
    checkExtractedID3v1Frame(3)

  it should "handle an error on upstream" in :
    val exception = new IllegalStateException("Test exception: stream failure.")
    val source = Source.failed(exception)

    recoverToExceptionIf[IllegalStateException] {
      runStream(source)
    } map { actualException =>
      actualException should be(exception)
    }
