package de.oliver_heger.splaya.fs.impl

import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import de.oliver_heger.splaya.fs.StreamSource
import de.oliver_heger.tsthlp.StreamDataGenerator
import de.oliver_heger.tsthlp.TestFileSupport

/**
 * Test class for ''FSServiceImpl'' which tests functionality related to
 * resolving URIs.
 */
class TestFSServiceImplResolve extends JUnitSuite with TestFileSupport {
  /** Constant for the length of the test data. */
  private val Length = 256

  /** A stream generator for generating test data. */
  private lazy val generator: StreamDataGenerator = StreamDataGenerator()

  /** The service to be tested. */
  private var service: FSServiceImpl = _

  @Before def setUp() {
    service = new FSServiceImpl
    service.activate()
  }

  @After def tearDown() {
    removeTempFiles()
  }

  /**
   * Creates a file with test data. This file is used by the tests to resolve
   * its URI.
   * @return the data file
   * @throws IOException if an IO error occurs
   */
  private def createDataFile(): File =
    createTempFile { out =>
      out.print(generator.generateStreamContent(0, Length))
  }

  /**
   * Helper method for creating a data file and passing it to the resolver.
   * @return the resolved stream source
   */
  private def resolveFile(): StreamSource = {
    val f = createDataFile()
    val root = f.getParentFile().toURI.toString
    service.resolve(root, f.getName)
  }

  /**
   * Tests whether a valid URI can be resolved and its content read.
   */
  @Test def testResolveContent() {
    val source = resolveFile()
    val stream = source.openStream()
    val content = StreamDataGenerator.readStream(stream)
    stream.close()
    val strContent = new String(content)
    assert(generator.generateStreamContent(0, Length) === strContent)
  }

  /**
   * Tests whether the correct file size is returned by the resolved source.
   */
  @Test def testResolveSize() {
    val source = resolveFile()
    assert(Length === source.size)
  }

  /**
   * Tries to resolve a non existing URI.
   */
  @Test(expected = classOf[IOException]) def testResolveNonExistingURI() {
    val f = new File(".")
    service.resolve(f.getAbsolutePath, "a non existing URI!")
  }

  /**
   * Tries to resolve a non existing root URI.
   */
  @Test(expected = classOf[IOException]) def testResolveNonExitingRoot() {
    service.resolve("non-existing root!", "someFile.txt")
  }
}
