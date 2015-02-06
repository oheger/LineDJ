package de.oliver_heger.splaya

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.io.Source

object FileTestHelper {
  /** A string with defined test data. */
  val TestData = """|Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy
                   |eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam
                   |voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita
                   |kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem
                   |ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod
                   |tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At
                   |vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd
                   |gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet. Lorem ipsum
                   |dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor
                   |invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero
                   |eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no
                   |sea takimata sanctus est Lorem ipsum dolor sit amet.""".stripMargin

  /**
   * Helper method for converting a string to a byte array.
   * @param s the string
   * @return the byte array
   */
  def toBytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  /**
   * Returns a byte array with the complete test data.
   * @return the test bytes
   */
  def testBytes() = toBytes(TestData)
}

/**
 * A helper trait which can be used by test classes that need access to a file
 * with defined test data.
 *
 * This trait allows creating a test file. The file can be either be filled
 * initially with test data (for tests of reading functionality) or be read
 * later (for tests of writing functionality). There is also a cleanup method
 * for removing the file when it is no longer needed.
 */
trait FileTestHelper {
  import FileTestHelper._
  /** The test file managed by this trait. */
  var optTestFile: Option[Path] = None

  /**
   * Removes the temporary file if it exists.
   */
  def tearDownTestFile(): Unit = {
    optTestFile foreach Files.deleteIfExists
    optTestFile = None
  }

  /**
   * Returns the path to the temporary file managed by this trait. This method
   * requires that the file has already been initialized by one of the
   * ''create()'' methods.
   * @return the path to the managed file
   */
  def testFile = optTestFile.get

  /**
   * Creates a new temporary file reference with no content.
   * @return the path to the new file
   */
  def createFileReference(): Path = {
    tearDownTestFile()
    optTestFile = Some(File.createTempFile("FileTestHelper", "tmp").toPath)
    optTestFile.get
  }

  /**
   * Creates a new temporary file physically on disk which has the specified content.
   * @param content the content of the file
   * @return the path to the new file
   */
  def createDataFile(content: String = FileTestHelper.TestData): Path = {
    val path = createFileReference()
    Files.write(path, toBytes(content))
    path
  }

  /**
   * Reads the content of the data file and returns it as a string.
   * @return the content read from the data file
   */
  def readDataFile(): String = {
    val source = Source.fromFile(testFile.toFile)
    val result = source.getLines().mkString("\r\n")
    source.close()
    result
  }
}
