/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Path, Paths}
import scala.io.Source

object ScanResultEnhancerSpec {
  /** Constant for a medium directory. */
  private val Medium1 = "someMedium"

  /** Constant for another test medium directory. */
  private val Medium2 = "anotherMedium"

  /** The name of the file with the test medium content. */
  private val MediumFile = "test.plist"
}

/**
  * Test class for ''ScanResultEnhancer''.
  */
class ScanResultEnhancerSpec extends AnyFlatSpec with Matchers with BeforeAndAfter with
  FileTestHelper {

  import ScanResultEnhancerSpec._

  /**
    * Generates a medium ID based on the given directory name. The directory is
    * resolved based on the test root directory.
    *
    * @param path the relative root path of the medium
    * @return the resulting medium ID
    */
  private def createMediumID(path: String): MediumID =
    createMediumIDForPath(testDirectory resolve path)

  /**
    * Generates a medium ID for the specified root path.
    *
    * @param mediumRoot the root path for the medium
    * @return the resulting medium ID
    */
  private def createMediumIDForPath(mediumRoot: Path): MediumID =
    MediumID(mediumRoot.toString,
      Some(mediumRoot.resolve("playlist.settings").toString))

  /**
    * Converts a string read from the data file to a path.
    *
    * @param s        the string
    * @param rootPath the root path to resolve relative paths
    * @return the resulting path
    */
  private def toPath(s: String, rootPath: Path): Path =
    rootPath.resolve(Paths.get(s.trim()))

  /**
    * Creates a sequence with paths representing the content of a test medium.
    *
    * @param mid the test medium
    * @return the sequence with paths
    */
  private def createContentList(mid: MediumID): List[FileData] = {
    val rootPath = Paths get mid.mediumURI
    val source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(MediumFile))
    (source.getLines() map (s => FileData(toPath(s, rootPath), s.length))).toList
  }

  /**
    * Returns a map with file information for the first test medium.
    *
    * @return the map with file information for medium 1
    */
  private def createMediaFileMap(): Map[MediumID, List[FileData]] = {
    val mid = createMediumID(Medium1)
    Map(mid -> createContentList(mid))
  }

  /**
    * Creates a scan result object with the specified media files.
    *
    * @param media the map with media files
    * @return the scan result
    */
  private def createScanResult(media: Map[MediumID, List[FileData]] = createMediaFileMap()):
  MediaScanResult = MediaScanResult(testDirectory, media)

  "A ScanResultEnhancer" should "calculate a correct checksum" in {
    val mid = createMediumID(Medium1)
    val esr = ScanResultEnhancer enhance createScanResult()

    val checksum = esr.checksumMapping(mid)
    checksum.checksum.length should be > 8
    val validChars = (('A' to 'F') ++ ('0' to '9')).toSet
    checksum.checksum.forall(validChars.contains) shouldBe true
  }

  it should "generate the same checksum for the same content" in {
    val mid = createMediumID(Medium1)
    val esr1 = ScanResultEnhancer enhance createScanResult()
    val esr2 = ScanResultEnhancer enhance createScanResult()

    val checksum1 = esr1.checksumMapping(mid)
    val checksum2 = esr2.checksumMapping(mid)
    checksum1 should be(checksum2)
  }

  it should "generate the same checksum independent on absolute paths" in {
    val mid1 = createMediumID(Medium1)
    val mid2 = createMediumID(Medium2)
    val media = createMediaFileMap() + (mid2 -> createContentList(mid2))
    val esr = ScanResultEnhancer enhance createScanResult(media)

    esr.checksumMapping(mid1) should be(esr.checksumMapping(mid2))
  }

  it should "generate a checksum for a medium without a description file" in {
    val mid1 = createMediumID(Medium1)
    val mid2 = createMediumIDForPath(testDirectory).copy(mediumDescriptionPath = None)
    val media = createMediaFileMap() + (mid2 -> createContentList(mid2))
    val esr = ScanResultEnhancer enhance createScanResult(media)

    esr.checksumMapping(mid1) should be(esr.checksumMapping(mid2))
  }

  it should "generate the same checksum independent on the order of media files" in {
    val mid1 = createMediumID(Medium1)
    val mid2 = createMediumID(Medium2)
    val media = createMediaFileMap() + (mid2 -> createContentList(mid2).reverse)
    val esr = ScanResultEnhancer enhance createScanResult(media)

    esr.checksumMapping(mid1) should be(esr.checksumMapping(mid2))
  }

  it should "use file sizes for checksum generation" in {
    val mid1 = createMediumID(Medium1)
    val mid2 = createMediumIDForPath(testDirectory).copy(mediumDescriptionPath = None)
    val contentList = createContentList(mid2)
    val modifiedContent = contentList.head.copy(
      size = contentList.head.size + 1) :: contentList.tail
    val media = createMediaFileMap() + (mid2 -> modifiedContent)
    val esr = ScanResultEnhancer enhance createScanResult(media)

    esr.checksumMapping(mid1) should not be esr.checksumMapping(mid2)
  }

  it should "pass the scan result to the enhanced result" in {
    val scanResult = createScanResult()

    val esr = ScanResultEnhancer enhance scanResult
    esr.scanResult should be(scanResult)
  }
}
