package de.oliver_heger.splaya.engine.io

import org.scalatest.junit.JUnitSuite
import org.junit.Before
import org.apache.commons.vfs2.VFS
import de.oliver_heger.splaya.tsthlp.StreamDataGenerator
import java.io.File
import org.junit.After
import java.io.PrintStream
import java.io.FileOutputStream
import org.junit.Test
import java.io.IOException

/**
 * Test class for ''SourceResolverImpl''.
 */
class TestSourceResolverImpl extends JUnitSuite {
  /** Constant for the length of the test data. */
  private val Length = 256

  /** A stream generator for generating test data. */
  private lazy val generator: StreamDataGenerator = StreamDataGenerator()

  /** A list with temporary files which have to be removed at test end. */
  private var tempFiles: List[File] = _

  /** The resolver to be tested. */
  private var resolver: SourceResolverImpl = _

  @Before def setUp() {
    tempFiles = List.empty
    val manager = VFS.getManager()
    resolver = new SourceResolverImpl(manager)
  }

  @After def tearDown() {
    tempFiles foreach (_.delete())
  }

  /**
   * Creates a new temporary file. It will be automatically removed after the
   * current test case.
   * @return the temporary file
   * @throws IOException if an IO error occurs
   */
  private def createTempFile(): File = {
    val file = File.createTempFile("TestSourceResolver", "tmp")
    tempFiles = file :: tempFiles
    file
  }

  /**
   * Creates a file with test data. This file is used by the tests to resolve
   * its URI.
   * @return the data file
   * @throws IOException if an IO error occurs
   */
  private def createDataFile(): File = {
    val file = createTempFile()
    val out = new PrintStream(new FileOutputStream(file))
    out.print(generator.generateStreamContent(0, Length))
    out.close()
    file
  }

  /**
   * Helper method for creating a data file and passing it to the resolver.
   * @return the resolved stream source
   */
  private def resolveFile(): StreamSource =
    resolver.resolve(createDataFile().toURI.toString)

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
    resolver.resolve("a non existing URI!")
  }
}
