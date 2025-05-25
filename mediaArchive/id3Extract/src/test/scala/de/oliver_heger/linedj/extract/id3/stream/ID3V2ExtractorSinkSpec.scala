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

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Concat, FileIO, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}

import java.nio.file.Paths
import scala.concurrent.Future

object ID3V2ExtractorSinkSpec:
  /** The name of the test file. */
  private val TestFile = "/testID3v2Data.bin"

  /**
    * Returns a [[Source]] for the test file that uses the given chunk size.
    *
    * @param chunkSize the chunk size
    * @return the [[Source]] for reading the test file
    */
  private def testFileSource(chunkSize: Int = 8192): Source[ByteString, Any] =
    val fileURI = getClass.getResource(TestFile).toURI
    val path = Paths.get(fileURI)
    FileIO.fromPath(path, chunkSize)
end ID3V2ExtractorSinkSpec

/**
  * Test class for [[ID3v2ExtractorSink]].
  */
class ID3V2ExtractorSinkSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues:
  def this() = this(ActorSystem("ID3v2ExtractorStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ID3V2ExtractorSinkSpec.*

  /**
    * Runs a stream from the given source via the extractor sink and returns
    * a [[Future]] with the providers produced by the sink.
    *
    * @param source   the source of the stream
    * @param tagLimit the optional tag size limit
    * @return a [[Future]] with the stream result
    */
  private def runStream(source: Source[ByteString, Any],
                        tagLimit: Int = Int.MaxValue): Future[List[MetadataProvider]] =
    val sink = new ID3v2ExtractorSink(tagLimit)
    source.runWith(sink)

  /**
    * Runs a stream from the given source via the extractor stage and checks 
    * the providers returned by the stage.
    *
    * @param source the source of the stream
    * @return a [[Future]] with the test result
    */
  private def runAndCheckStream(source: Source[ByteString, Any]): Future[Assertion] =
    runStream(source).map(checkTestFileResult)

  /**
    * Checks the providers returned by the test stage.
    *
    * @param providers the list with providers
    * @return the result of the check
    */
  private def checkTestFileResult(providers: List[MetadataProvider]): Assertion = {
    providers should have size 2
    providers.head.title should not be providers(1).title
    forEvery(providers)(checkTestFileProvider)
  }

  /**
    * Checks whether a provider returned by the stage has the expected
    * content. There are two providers for different ID3v2 versions with 
    * slightly different properties.
    *
    * @param provider the provider to check
    * @return the result of the check
    */
  private def checkTestFileProvider(provider: MetadataProvider): Assertion =
    provider.title match
      case Some("Testtitle") =>
        provider.artist should be(Some("Testinterpret"))
        provider.inceptionYear should be(Some(2006))
      case Some("Test Title") =>
        provider.title should be(Some("Test Title"))
        provider.album should be(Some("Test Album"))
        provider.artist should be(Some("Test Artist"))
      case t =>
        fail("Unexpected title extracted: " + t)

  "ID3v2ExtractorStage" should "return correct metadata providers" in :
    runAndCheckStream(testFileSource())

  it should "handle a small chunk size" in :
    runAndCheckStream(testFileSource(chunkSize = 16))

  it should "complete the stream when no more ID3 frames are expected" in :
    val fileSource = testFileSource()
    val furtherDataSource = Source.cycle(() => ByteString(FileTestHelper.TestData).grouped(64))
    val streamSource = Source.combine(fileSource, furtherDataSource)(Concat(_))

    runAndCheckStream(streamSource)

  it should "ignore incomplete metadata in the stream" in :
    val source = testFileSource(chunkSize = 16).take(135)

    runStream(source).map { providers =>
      providers should have size 1
    }

  it should "evaluate the tag size limit correctly" in :
    runStream(testFileSource(), tagLimit = 12).map { providers =>
      val provider = providers.find(_.title.contains("Testtitle")).value

      provider.artist shouldBe empty
    }

  it should "handle upstream failures gracefully" in :
    val exception = new IllegalStateException("Test exception: stream failure.")
    val source = Source.failed(exception)

    recoverToExceptionIf[IllegalStateException] {
      runStream(source)
    } map { actualException =>
      actualException should be(exception)
    }
