package de.oliver_heger.linedj.media

import java.nio.file.{Path, Paths}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.FileData
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.io.Source

object MediumIDCalculatorSpec {
  /** The name of the file with the test medium content. */
  private val MediumFile = "test.plist"

  /** The expected checksum of the test medium. */
  private val CheckSum = "17131856"

  /** A medium URI. */
  private val MediumURI = "test://TestMediumURI"

  /** A test medium ID. */
  private val TestID = MediumID(MediumURI, None)

  /** A scan result associated with the test medium. */
  private val ScanResult = MediaScanResult(Paths get "RootPat", Map.empty)
}

/**
 * Test class for ''MediumIDCalculator''.
 */
class MediumIDCalculatorSpec extends FlatSpec with Matchers with BeforeAndAfter with
FileTestHelper {

  import MediumIDCalculatorSpec._

  after {
    tearDownTestFile()
  }

  /**
   * Converts a string read from the data file to a path.
   * @param s the string
   * @return the resulting path
   */
  private def toPath(s: String): Path = testDirectory.resolve(Paths.get(s.trim()))

  /**
   * Creates a sequence with paths representing the content of a test medium.
   * @return the sequence with paths
   */
  private def createContentList(): Seq[FileData] = {
    val source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(MediumFile))
    (source.getLines() map (s => FileData(toPath(s), s.length))).toSeq
  }

  "A MediumIDCalculator" should "calculate the correct medium ID" in {
    val calculator = new MediumIDCalculator
    calculator.calculateMediumID(testDirectory, TestID, ScanResult, createContentList())
      .checksum should be(CheckSum)
  }

  it should "return the same ID independent on ordering" in {
    val calculator = new MediumIDCalculator

    val content = createContentList().reverse
    calculator.calculateMediumID(testDirectory, TestID, ScanResult, content).checksum should be(CheckSum)
  }

  it should "return different IDs for different content" in {
    val calculator = new MediumIDCalculator

    val content = FileData(createPathInDirectory("newTrack.mp3"), 1) :: createContentList().toList
    calculator.calculateMediumID(testDirectory, TestID, ScanResult, content)
      .checksum should not be CheckSum
  }

  it should "generate a correct URI path mapping" in {
    val calculator = new MediumIDCalculator

    val files = createContentList()
    val data = calculator.calculateMediumID(testDirectory, TestID, ScanResult, files)
    data.fileURIMapping should have size files.size
    data.fileURIMapping.values forall files.contains shouldBe true
  }

  it should "pass through the medium ID and scan result" in {
    val calculator = new MediumIDCalculator

    val data = calculator.calculateMediumID(testDirectory, TestID, ScanResult, createContentList())
    data.mediumID should be (TestID)
    data.scanResult should be(ScanResult)
  }
}
