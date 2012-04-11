package de.oliver_heger.splaya.engine.io

import org.scalatest.junit.JUnitSuite
import org.junit.Before
import org.scalatest.mock.EasyMockSugar
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Test class for ''SourceStreamWrapperFactoryImpl''.
 */
class TestSourceStreamWrapperFactoryImpl extends JUnitSuite with EasyMockSugar {
  /** A mock for the source buffer manager. */
  private var bufferManager : SourceBufferManager = _

  /** A mock for the factory for temporary files. */
  private var tempFactory : TempFileFactory = _

  /** The factory to be tested. */
  private var factory : SourceStreamWrapperFactoryImpl = _

  @Before def setUp() {
    bufferManager = mock[SourceBufferManager]
    tempFactory = mock[TempFileFactory]
    factory = new SourceStreamWrapperFactoryImpl(bufferManager, tempFactory)
  }

  /**
   * Tests whether the correct buffer manager is returned.
   */
  @Test def testBufferManager() {
    assert(bufferManager === factory.bufferManager)
  }

  /**
   * Tests whether a stream can be created.
   */
  @Test def testCreateStream() {
    val strm : InputStream = new ByteArrayInputStream("This is a test".getBytes())
    val length = 20120225L
    val wrapper = factory.createStream(strm, length)
    assert(strm === wrapper.currentStream)
    assert(length === wrapper.length)
  }
}
