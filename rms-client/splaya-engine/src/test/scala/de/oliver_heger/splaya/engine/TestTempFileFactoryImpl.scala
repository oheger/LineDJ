package de.oliver_heger.splaya.engine

import org.scalatest.junit.JUnitSuite
import org.junit.Before
import java.io.File
import org.junit.Test
import java.io.PrintStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import scala.collection.mutable.ArrayBuffer

/**
 * Test class for {@code TempFileFactoryImpl}.
 */
class TestTempFileFactoryImpl extends JUnitSuite {
  /** Constant for the property with the temporary directory. */
  private val PropTempDir = "java.io.tmpdir"

  /** Constant for the file prefix used by this test class. */
  private val FilePrefix = "TestTempFileFactoryImpl"

  /** The file representing the temporary directory.*/
  private var tempDir: File = _

  @Before def setUp() {
    tempDir = new File(System.getProperty(PropTempDir))
  }

  /**
   * Returns a set with the names of all temporary files which match the test
   * criteria.
   * @return the set with the names of the files found
   */
  private def tempFiles(): Set[String] = {
    var files = Set[String]()
    tempDir.list().foreach(
      name => if (name.startsWith(FilePrefix)) files += name)
    files
  }

  /**
   * Tests whether default settings for prefix or suffix are used if no values
   * are provided.
   */
  @Test def testDefaultSettings() {
    val factory = new TempFileFactoryImpl
    assert(factory.filePrefix === factory.DefaultFilePrefix)
    assert(factory.fileSuffix === factory.DefaultFileSuffix)
  }

  /**
   * Tests the creation of a temporary file.
   */
  @Test def testCreateTempFile() {
    val factory = new TempFileFactoryImpl(FilePrefix, null)
    val files1 = tempFiles()
    val temp = factory.createFile()
    val files2 = tempFiles()
    assert(files2.size === files1.size + 1)
    assert(temp.delete() === true)
    val files3 = tempFiles()
    assert(files1 === files3)
  }

  /**
   * Tests whether a test file can be written and read.
   */
  @Test def testWriteTempfile() {
    val factory = new TempFileFactoryImpl(FilePrefix, null)
    val temp = factory.createFile()
    val out = new PrintStream(temp.outputStream())
    val content = "This is a test!"
    out.print(content)
    out.close()
    val in = temp.inputStream()
    val buf = ArrayBuffer.empty[Byte]
    var c = in.read()
    while (c != -1) {
      buf += c.toByte
      c = in.read
    }
    in.close()
    assert(content === new String(buf.toArray))
    assert(content.length === temp.length)
    assert(temp.delete() === true)
  }
}