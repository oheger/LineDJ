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
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult, MetadataProcessingSuccess}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}

import java.nio.file.{Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, TimeoutException}

object ExtractorStageSpec:
  /** The medium ID used by tests. */
  private val TestMediumID = MediumID("testMedium", Some("testPath"))

  /** A prefix for file names causing a delay in the extractor function. */
  private val DelayPrefix = "delay"

  /** The default timeout used by the test extractor stage. */
  private val DefaultTimeout = 1.second

  /**
    * Stores exceptions to simulate failures of the extractor process. Since
    * exceptions cannot be compared via equals, this map ensures that for each
    * test file only a single exception instance is created.
    */
  private val extractionExceptions = new ConcurrentHashMap[String, Throwable]

  /**
    * Returns a [[MediaMetadata]] object with properties derived from the 
    * given name.
    *
    * @param name the name of a media file
    * @return metadata for this file
    */
  private def createMetadata(name: String): MediaMetadata =
    MediaMetadata(
      title = Some(name + "-Title"),
      artist = Some(name + "-Artist"),
      album = Some(name + "-Album"),
      size = name.length,
      checksum = name + "-check"
    )

  /**
    * Simulates the extraction of metadata for a file. The function returns
    * dummy data derived from the name of the file. Some file names are handled
    * in a special way to simulate timeouts or processing errors.
    *
    * @param path   the path of the media file
    * @param system the actor system
    * @return a [[Future]] with extracted metadata
    */
  private def extractorFunc(path: Path, system: ActorSystem): Future[MediaMetadata] =
    given ExecutionContext = system.dispatcher

    Future {
      val fileName = path.getFileName.toString
      if fileName.startsWith("error_") then
        throw extractorException(fileName)

      simulateDelay(fileName)
      createMetadata(fileName)
    }

  /**
    * Simulates a delay in the extraction process of a file. If the file name
    * is of the form ''delayXXX.mp3'' where XXX is a number, this function
    * extracts this number and sleeps for the corresponding milliseconds.
    *
    * @param fileName the file name
    */
  private def simulateDelay(fileName: String): Unit =
    if fileName.startsWith(DelayPrefix) then
      val delayMillis = fileName.substring(DelayPrefix.length, fileName.indexOf('.')).toLong
      Thread.sleep(delayMillis)

  /**
    * Generates the name of a test file whose simulated metadata extraction is
    * delayed by the given number of milliseconds.
    *
    * @param delayMills the delay in millis
    * @return the name of the test file with this delay
    */
  private def delayedFile(delayMills: Int): String = s"$DelayPrefix$delayMills.mp3"

  /**
    * Returns a provider for extractor functions that yields the test extractor
    * function for files with the "mp3" extension, ''None'' for files with no
    * extension, and throws an exception otherwise.
    *
    * @return the provider for extractor functions
    */
  private def createExtractorFunctionProvider(): ExtractorFunctionProvider = {
    case "mp3" => Some(extractorFunc)
    case "" => None
    case e => throw new IllegalArgumentException("Invalid extension: " + e)
  }

  /**
    * Converts the given name for a test media file to a [[Path]].
    *
    * @param name the file name
    * @return the path for this test file
    */
  private def toPath(name: String): Path = Paths.get(name)

  /**
    * A converter function from paths to URIs used by the test stage. Since the
    * paths used in tests are rather simple, so can be the conversion.
    *
    * @param path the path to convert
    * @return the URI for this path
    */
  private def uriForPath(path: Path): MediaFileUri =
    MediaFileUri(path.toUri.toString)

  /**
    * Generates a successful processing result for the test media file with the
    * given name.
    *
    * @param name the name of the test file
    * @return the processing result for this file
    */
  private def successResultFor(name: String): MetadataProcessingResult =
    MetadataProcessingSuccess(TestMediumID, uriForPath(toPath(name)), createMetadata(name))

  /**
    * Generates a successful processing result for an unsupported media file.
    * Here, only dummy metadata are contained.
    *
    * @param name the name of the test file
    * @return the processing result for this file
    */
  private def unsupportedResultFor(name: String): MetadataProcessingResult =
    MetadataProcessingSuccess(
      mediumID = TestMediumID,
      uri = uriForPath(toPath(name)),
      metadata = MediaMetadata.UndefinedMediaData
    )

  /**
    * Generates an error processing result for the test file with the given
    * name.
    *
    * @param name the name of the test file
    * @return the error result for this file
    */
  private def errorResultFor(name: String): MetadataProcessingResult =
    MetadataProcessingError(
      mediumID = TestMediumID,
      uri = uriForPath(toPath(name)),
      exception = extractorException(name)
    )

  /**
    * Generates an exception to simulate an error when extracting metadata.
    *
    * @param fileName the name of the affected file
    * @return the exception simulating an extraction error
    */
  private def extractorException(fileName: String): Throwable =
    extractionExceptions.computeIfAbsent(
      fileName,
      name => new IllegalStateException("Test exception: Failed to extract metadata for file " + name)
    )
end ExtractorStageSpec

/**
  * Test class for [[ExtractorStage]].
  */
class ExtractorStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues:
  def this() = this(ActorSystem("ExtractorStageSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ExtractorStageSpec.*

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
      errorResult.uri should be(uriForPath(toPath(testFiles(1))))
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
      val failureUri = uriForPath(toPath(testFiles.head))
      failureResults.head.uri should be(failureUri)
      successResults.find(_.uri == failureUri) shouldBe empty
    }
    