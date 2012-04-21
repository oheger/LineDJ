package de.oliver_heger.splaya.engine.io

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Before
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert._
import org.junit.Test
import java.io.ByteArrayInputStream
import org.easymock.EasyMock
import java.util.Arrays

/**
 * Test class for {@code SourceStreamWrapper}.
 */
class TestSourceStreamWrapper extends JUnitSuite with EasyMockSugar {
  /** Constant for a test text to be streamed.*/
  val Text = """Lorem ipsum dolor sit amet, consectetuer adipiscing elit.
    Aenean commodo ligula eget dolor. Aenean massa. Cum sociis natoque
    penatibus et magnis dis parturient montes, nascetur ridiculus mus. Donec
    quam felis, ultricies nec, pellentesque eu, pretium quis, sem. Nulla
    consequat massa quis enim. Donec pede justo, fringilla vel, aliquet nec,
    vulputate eget, arcu. In enim justo, rhoncus ut, imperdiet a, venenatis
    vitae, justo. Nullam dictum felis eu pede mollis pretium. Integer
    tincidunt. Cras dapibus."""

  /** A mock for the buffer manager.*/
  var bufferManager: SourceBufferManager = _

  @Before def setUp() {
    bufferManager = mock[SourceBufferManager]
  }

  /**
   * Returns a test stream which wraps the whole test text.
   */
  private def wrappedStream() = new ByteArrayInputStream(Text.getBytes())

  /**
   * Creates a helper object for reset implementations.
   * @return the helper
   */
  private def createResetHelper(): StreamResetHelper = {
    val factory = niceMock[TempFileFactory]
    EasyMock.replay(factory)
    val helper = new StreamResetHelper(factory)
    helper
  }

  /**
   * Tests the default stream reset helper.
   */
  @Test def testDefaultResetHelper() {
    val stream = new SourceStreamWrapper(mock[TempFileFactory], wrappedStream(),
      Text.length, bufferManager)
    assertNotNull("No reset helper", stream.streamResetHelper)
  }

  /**
   * Tests whether the stream contains the expected content.
   * @param stream the stream to be read
   * @param expText the expected text content
   */
  private def checkReadStream(stream: SourceStreamWrapper, expText: String) {
    val builder = new StringBuilder
    var c = stream.read()
    while (c != -1) {
      builder += c.toChar
      c = stream.read()
    }
    assert(expText === builder.toString())
  }

  /**
   * Tests whether the whole stream can be read in a single step.
   */
  @Test def testReadWholeStream() {
    val helper = createResetHelper()
    val stream = new SourceStreamWrapper(helper, wrappedStream(), Text.length,
      bufferManager)
    checkReadStream(stream, Text)
    assertNull("Got a current stream", stream.currentStream)
  }

  /**
   * Tests whether the stream length can be changed later.
   */
  @Test def testChangeLength() {
    val len = Text.length / 2
    val stream = new SourceStreamWrapper(createResetHelper(), wrappedStream(),
      Text.length, bufferManager)
    stream changeLength len
    checkReadStream(stream, Text.substring(0, len))
  }

  /**
   * Tests whether the limit of the stream length is taken into account.
   */
  @Test def testReadStreamLimit() {
    val length = 100
    val inputStream = wrappedStream()
    val stream = new SourceStreamWrapper(createResetHelper(), inputStream,
      length, bufferManager)
    checkReadStream(stream, Text.substring(0, length))
    assert(inputStream === stream.currentStream)
  }

  /**
   * Tests whether a stream which spans multiple temporary files can be read.
   */
  @Test def testReadMultipleTempFiles() {
    val temp1 = mock[TempFile]
    val temp2 = mock[TempFile]
    val helper = mock[StreamResetHelper]
    expecting {
      EasyMock.expect(temp1.inputStream()).andReturn(wrappedStream())
      EasyMock.expect(temp2.inputStream()).andReturn(wrappedStream())
      EasyMock.expect(bufferManager.next()).andReturn(temp1)
      EasyMock.expect(bufferManager.next()).andReturn(temp2)
    }

    whenExecuting(temp1, temp2, bufferManager) {
      val stream = new SourceStreamWrapper(createResetHelper(), null,
        2 * Text.length, bufferManager)
      checkReadStream(stream, Text + Text)
    }
  }

  /**
   * Tests whether the mark operation is supported.
   */
  @Test def testMarkSupported() {
    val stream = new SourceStreamWrapper(createResetHelper(), null, Text.length,
      bufferManager)
    assert(stream.markSupported() === true)
  }

  /**
   * Tests whether the reset helper is correctly invoked during read operations.
   */
  @Test def testReadWithResetHelper() {
    val resetHelper = mock[StreamResetHelper]
    val buf = new Array[Byte](100)
    val readLen = 10
    expecting {
      EasyMock.expect(resetHelper.read(buf, 0, buf.length)).andReturn(readLen)
      resetHelper.push(buf, 0, buf.length)
    }

    whenExecuting(resetHelper) {
      val stream = new SourceStreamWrapper(resetHelper, wrappedStream(),
        Text.length(), bufferManager)
      assert(stream.read(buf) === buf.length)
      assert(buf.length === stream.currentPosition)
    }
    val txt = new String(Arrays.copyOfRange(buf, readLen, buf.length))
    assert(Text.substring(0, buf.length - readLen) === txt)
  }

  /**
   * Tests whether a mark operation is delegated to the helper object.
   */
  @Test def testMark() {
    val resetHelper = mock[StreamResetHelper]
    expecting {
      resetHelper.mark()
    }

    whenExecuting(resetHelper) {
      val stream = new SourceStreamWrapper(resetHelper, wrappedStream(),
        Text.length(), bufferManager)
      stream.mark(20120106)
    }
  }

  /**
   * Tests whether a reset operation is delegated to the helper object.
   */
  @Test def testReset() {
    val resetHelper = mock[StreamResetHelper]
    expecting {
      resetHelper.reset()
    }

    whenExecuting(resetHelper) {
      val stream = new SourceStreamWrapper(resetHelper, wrappedStream(),
        Text.length(), bufferManager)
      stream.reset()
    }
  }

  /**
   * Tests the close operation.
   */
  @Test def testClose() {
    val helper = createResetHelper()
    val stream = new SourceStreamWrapper(helper, wrappedStream(), Text.length,
      bufferManager)
    stream.read()
    assertNotNull("No current stream", stream.currentStream)
    stream.close()
    assertNull("Still got a current stream", stream.currentStream)
  }

  /**
   * Tests whether the presence of a stream is checked before it gets closed.
   */
  @Test def testCloseMulti() {
    val helper = createResetHelper()
    val stream = new SourceStreamWrapper(helper, wrappedStream(), Text.length,
      bufferManager)
    stream.read()
    stream.close()
    stream.close()
  }
}
