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
   * Creates a sequence with paths representing the content of a test medium.
   * @return the sequence with paths
   */
  private def createContentList(): Seq[Path] = {
    val source = Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(MediumFile))
    (source.getLines() map (s => testDirectory.resolve(Paths.get(s.trim())))).toSeq
  }

  "A MediumIDCalculator" should "calculate the correct medium ID" in {
    val calculator = new MediumIDCalculator
    calculator.calculateMediumID(testDirectory, createContentList()).mediumID should be(MediumID)
  }

  it should "return the same ID independent on ordering" in {
    val calculator = new MediumIDCalculator

    val content = createContentList().reverse
    calculator.calculateMediumID(testDirectory, content).mediumID should be(MediumID)
  }

  it should "return different IDs for different content" in {
    val calculator = new MediumIDCalculator

    val content = createPathInDirectory("newTrack.mp3") :: createContentList().toList
    calculator.calculateMediumID(testDirectory, content).mediumID should not be MediumID
  }

  it should "generate a correct URI path mapping" in {
    val calculator = new MediumIDCalculator

    val files = createContentList()
    val data = calculator.calculateMediumID(testDirectory, files)
    data.fileURIMapping should have size files.size
    data.fileURIMapping.values forall files.contains shouldBe true
  }
}
