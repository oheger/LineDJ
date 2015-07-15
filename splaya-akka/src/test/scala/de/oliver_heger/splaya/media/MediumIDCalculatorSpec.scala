package de.oliver_heger.splaya.media

import java.nio.file.{Path, Paths}

import de.oliver_heger.splaya.FileTestHelper
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.io.Source

object MediumIDCalculatorSpec {
  /** The name of the file with the test medium content. */
  private val MediumFile = "test.plist"

  /** The expected ID of the test medium. */
  private val MediumID = "17131856"

  /** A medium URI. */
  private val MediumURI = "test://TestMediumURI"
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
  private def createContentList(): Seq[MediaFile] = {
    val source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(MediumFile))
    (source.getLines() map (s => MediaFile(toPath(s), s.length))).toSeq
  }

  "A MediumIDCalculator" should "calculate the correct medium ID" in {
    val calculator = new MediumIDCalculator
    calculator.calculateMediumID(testDirectory, MediumURI, createContentList()).mediumID should
      be(MediumID)
  }

  it should "return the same ID independent on ordering" in {
    val calculator = new MediumIDCalculator

    val content = createContentList().reverse
    calculator.calculateMediumID(testDirectory, MediumURI, content).mediumID should be(MediumID)
  }

  it should "return different IDs for different content" in {
    val calculator = new MediumIDCalculator

    val content = MediaFile(createPathInDirectory("newTrack.mp3"), 1) :: createContentList().toList
    calculator.calculateMediumID(testDirectory, MediumURI, content).mediumID should not be MediumID
  }

  it should "generate a correct URI path mapping" in {
    val calculator = new MediumIDCalculator

    val files = createContentList()
    val data = calculator.calculateMediumID(testDirectory, MediumURI, files)
    data.fileURIMapping should have size files.size
    data.fileURIMapping.values forall files.contains shouldBe true
  }

  it should "pass through the medium URI" in {
    val calculator = new MediumIDCalculator

    val data = calculator.calculateMediumID(testDirectory, MediumURI, createContentList())
    data.mediumURI should be (MediumURI)
  }
}
