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

package de.oliver_heger.linedj.extract.metadata

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult, MetadataProcessingSuccess}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}

import scala.concurrent.duration.*
import scala.concurrent.{Future, TimeoutException}

/**
  * Test class for [[ExtractorStage]].
  */
class ExtractorStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues:
  def this() = this(ActorSystem("ExtractorStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ExtractorTestHelper.*

  /**
    * Runs a stream that passes the given test files through an extractor 
    * stage and returns the results.
    *
    * @param fileNames the names of the test files to process
    * @param timeout   an optional timeout for the test stage
    * @return a [[Future]] with the received processing results
    */
  private def runStream(fileNames: List[String],
                        timeout: FiniteDuration = DefaultTimeout): Future[List[MetadataProcessingResult]] =
    val extractorStage = new ExtractorStage(createExtractorFunctionProvider(), 250.millis, TestMediumID, uriForPath)
    val source = Source(fileNames).map { name =>
      FileData(toPath(name), 42)
    }
    val sink = Sink.fold[List[MetadataProcessingResult], MetadataProcessingResult](List.empty) { (lst, res) =>
      res :: lst
    }

    source.via(extractorStage).runWith(sink)

  /**
    * Runs a stream that passes the given test file through an extractor stage
    * and checks whether the expected results are returned.
    *
    * @param fileNames       the names of the test files to process
    * @param expectedResults the expected extraction results
    * @return the result of the check
    */
  private def checkExtraction(fileNames: List[String],
                              expectedResults: List[MetadataProcessingResult]): Future[Assertion] =
    runStream(fileNames) map { results =>
      results should contain theSameElementsAs expectedResults
    }

  "An ExtractorStage" should "return metadata for files with supported file extensions" in :
    val testFiles = List("song1.mp3", "anotherSong.mp3", "greatMusic.mp3")
    val expectedResults = testFiles map successResultFor

    checkExtraction(testFiles, expectedResults)

  it should "handle an empty source" in :
    runStream(Nil) map { results =>
      results shouldBe empty
    }

  it should "return a success result with empty metadata for an unsupported audio file" in :
    val testFiles = List("unsupported", "supported.mp3")
    val unsupportedResult = unsupportedResultFor(testFiles.head)
    val expectedResults = List(unsupportedResult, successResultFor(testFiles(1)))

    checkExtraction(testFiles, expectedResults)

  it should "handle a source with only unsupported files" in :
    val testFiles = List("unsupported1", "unsupported2", "unsupported3", "andAnotherUnsupported")
    val expectedResults = testFiles map unsupportedResultFor

    checkExtraction(testFiles, expectedResults)

  it should "handle a failed Future returned from the extractor function" in :
    val file1 = "success1.mp3"
    val file2 = "success2.mp3"
    val errorFile = "error_InvalidFile.mp3"
    val testFiles = List(file1, errorFile, file2)
    val expectedResults = List(successResultFor(file1), successResultFor(file2), errorResultFor(errorFile))

    checkExtraction(testFiles, expectedResults)

  it should "handle a source with only files causing extractor errors" in :
    val testFiles = List("error_one.mp3", "error_oneMore.mp3", "error_multiple.mp3")
    val expectedResults = testFiles map errorResultFor

    checkExtraction(testFiles, expectedResults)

  it should "produce a failure result when the extractor function times out" in :
    val testFiles = List("file1.mp3", delayedFile(1000), "file2.mp3")

    runStream(testFiles, timeout = 250.millis) map { results =>
      results should have size 3
      results should contain allOf(successResultFor(testFiles.head), successResultFor(testFiles.last))
      val errorResult = results.find(_.isInstanceOf[MetadataProcessingError]).value
      errorResult.mediumID should be(TestMediumID)
      errorResult.uri should be(uriForName(testFiles(1)))
      errorResult.asInstanceOf[MetadataProcessingError].exception shouldBe a[TimeoutException]
    }

  it should "handle a source with only files causing timeouts" in :
    val testFiles = List(delayedFile(1000), delayedFile(1010))

    runStream(testFiles, timeout = 50.millis) map { results =>
      results should have size 2
    }

  it should "handle results from the extractor function received after a timeout" in :
    val successFiles = (250 to 254).map(delayedFile).toList
    val testFiles = delayedFile(500) :: successFiles

    runStream(testFiles, timeout = 300.millis) map { results =>
      val (successResults, failureResults) = results.partition(_.isInstanceOf[MetadataProcessingSuccess])
      failureResults should have size 1
      successResults should have size successFiles.size
      val failureUri = uriForName(testFiles.head)
      failureResults.head.uri should be(failureUri)
      successResults.find(_.uri == failureUri) shouldBe empty
    }
    