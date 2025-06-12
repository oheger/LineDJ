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

import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingResult, MetadataProcessingSuccess}
import org.apache.pekko.actor.ActorSystem

import java.nio.file.{Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/**
  * A test helper object providing functionality that is useful for testing
  * metadata extraction logic. For instance, the object can generate metadata
  * for test files and can simulate extraction processes.
  */
object ExtractorTestHelper:
  /** The medium ID used by tests. */
  final val TestMediumID = MediumID("testMedium", Some("testPath"))

  /** The default timeout used by the test extractor stage. */
  final val DefaultTimeout = 1.second

  /** A prefix for file names causing a delay in the extractor function. */
  private val DelayPrefix = "delay"

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
  def createMetadata(name: String): MediaMetadata =
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
  def extractorFunc(path: Path, system: ActorSystem): Future[MediaMetadata] =
    given ExecutionContext = system.dispatcher

    Future {
      val fileName = path.getFileName.toString
      if fileName.startsWith("error_") then
        throw extractorException(fileName)

      simulateDelay(fileName)
      createMetadata(fileName)
    }

  /**
    * Generates the name of a test file whose simulated metadata extraction is
    * delayed by the given number of milliseconds.
    *
    * @param delayMills the delay in millis
    * @return the name of the test file with this delay
    */
  def delayedFile(delayMills: Int): String = s"$DelayPrefix$delayMills.mp3"

  /**
    * Returns a provider for extractor functions that yields the test extractor
    * function for files with the "mp3" extension, ''None'' for files with no
    * extension, and throws an exception otherwise.
    *
    * @return the provider for extractor functions
    */
  def createExtractorFunctionProvider(): ExtractorFunctionProvider = {
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
  def toPath(name: String): Path = Paths.get(name)

  /**
    * A converter function from paths to URIs used by the test stage. Since the
    * paths used in tests are rather simple, so can be the conversion.
    *
    * @param path the path to convert
    * @return the URI for this path
    */
  def uriForPath(path: Path): MediaFileUri =
    MediaFileUri(path.toUri.toString)

  /**
    * A convenience function that determines the URI for the test file with the
    * given name. This is equivalent to converting the name to a [[Path]], and
    * then obtaining the URI for this path.
    *
    * @param name the name of the test file
    * @return the URI of this test file
    */
  def uriForName(name: String): MediaFileUri =
    uriForPath(toPath(name))

  /**
    * Generates a successful processing result for the test media file with the
    * given name.
    *
    * @param name the name of the test file
    * @return the processing result for this file
    */
  def successResultFor(name: String): MetadataProcessingResult =
    MetadataProcessingSuccess(TestMediumID, uriForPath(toPath(name)), createMetadata(name))

  /**
    * Generates a successful processing result for an unsupported media file.
    * Here, only dummy metadata are contained.
    *
    * @param name the name of the test file
    * @return the processing result for this file
    */
  def unsupportedResultFor(name: String): MetadataProcessingResult =
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
  def errorResultFor(name: String): MetadataProcessingResult =
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
  def extractorException(fileName: String): Throwable =
    extractionExceptions.computeIfAbsent(
      fileName,
      name => new IllegalStateException("Test exception: Failed to extract metadata for file " + name)
    )

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
