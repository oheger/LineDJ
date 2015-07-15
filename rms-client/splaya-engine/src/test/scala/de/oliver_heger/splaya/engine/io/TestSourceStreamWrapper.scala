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
import scala.collection.mutable.ListBuffer
import org.easymock.IAnswer

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
  private var bufferManager: SourceBufferManager = _

  /** A list builder for recording position changed events. */
  private var posChangedBuilder: ListBuffer[Long] = _

  /** A list builder for recording stream read events. */
  private var streamReadBuilder: ListBuffer[Long] = _

  @Before def setUp() {
    posChangedBuilder = ListBuffer.empty
    streamReadBuilder = ListBuffer.empty
    bufferManager = prepareBufferManagerMock()
  }

  /**
   * Prepares a mock for the buffer manager. The mock is prepared to expect
   * callbacks about position updates and stream reads.
   * @return the mock object
   */
  private def prepareBufferManagerMock(): SourceBufferManager = {
    val bufMan = mock[SourceBufferManager]
    bufMan.updateCurrentStreamReadPosition(EasyMock.anyLong())
    EasyMock.expectLastCall().andAnswer(new ListAppendAnswer(posChangedBuilder))
      .anyTimes()
    bufMan.streamRead(EasyMock.anyLong())
    EasyMock.expectLastCall().andAnswer(new ListAppendAnswer(streamReadBuilder))
      .anyTimes()
    bufMan
  }

  /**
   * Returns a test stream which wraps the whole test text.
   */
  private def wrappedStream() = new ByteArrayInputStream(Text.getBytes())

  /**
   * Creates a helper object for reset implementations.
   * @return the helper
   */
  private def createResetHelper(): MyResetHelper = {
    val factory = niceMock[TempFileFactory]
    EasyMock.replay(factory)
    new MyResetHelper(factory)
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
    assertTrue("Not end of stream", stream.isEndOfStream)
  }

  /**
   * Tests whether position changed events are triggered while the stream is
   * read.
   */
  @Test def testPositionChangedEvents() {
    def checkList(list: List[Long], idx: Int) {
      if (list.isEmpty) {
        assertEquals("Wrong number of events", Text.length+1, idx)
      } else {
        assertEquals("Wrong value at " + idx, idx, list.head)
        checkList(list.tail, idx + 1)
      }
    }

    EasyMock.replay(bufferManager)
    val stream = new SourceStreamWrapper(createResetHelper(), wrappedStream(),
      Text.length, bufferManager)
    checkReadStream(stream, Text)
    checkList(posChangedBuilder.toList, 1)
  }

  /**
   * Tests whether the buffer manager is notified when the stream was read.
   */
  @Test def testStreamReadEvent() {
    EasyMock.replay(bufferManager)
    val stream = new SourceStreamWrapper(createResetHelper(), wrappedStream(),
      Text.length, bufferManager)
    checkReadStream(stream, Text)
    assert(-1 === stream.read())
    assertEquals("Wrong number of read events", 1, streamReadBuilder.size)
    assertEquals("Wrong stream length", Text.length, streamReadBuilder(0))
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
   * Tests whether a reset operation updates the position in the stream.
   */
  @Test def testResetPositionUpdateEvent() {
    val resetHelper = niceMock[StreamResetHelper]
    val bufLen = 16L
    val buffer = new Array[Byte](bufLen.toInt)
    whenExecuting(resetHelper, bufferManager) {
      val stream = new SourceStreamWrapper(resetHelper, wrappedStream(),
        Text.length(), bufferManager)
      stream.read(buffer)
      stream.read(buffer)
      stream.reset()
    }
    assert(List(bufLen, 2 * bufLen, 0) === posChangedBuilder.toList)
  }

  /**
   * Tests that the end of stream flag is cleared after a reset operation.
   */
  @Test def testEndOfStreamAfterReset() {
    val resetHelper = niceMock[StreamResetHelper]
    val buffer = new Array[Byte](Text.length)
    whenExecuting(resetHelper, bufferManager) {
      val stream = new SourceStreamWrapper(resetHelper, wrappedStream(),
        Text.length(), bufferManager)
      stream.read(buffer)
      stream.read()
      assertTrue("Not end of stream", stream.isEndOfStream)
      stream.reset()
      assertFalse("Still end of stream", stream.isEndOfStream)
    }
  }

  /**
   * Tests that only a single stream read event is produced, even if the stream
   * is reset and read multiple times.
   */
  @Test def testOnlyASingleStreamReadEvent() {
    val resetHelper = niceMock[StreamResetHelper]
    val temp = mock[TempFile]
    val buffer = new Array[Byte](Text.length)

    expecting {
      // This is necessary only because we do not have a functional reset
      // helper. In reality, the content of the stream would be read from the
      // reset helper.
      EasyMock.expect(bufferManager.next()).andReturn(temp)
      EasyMock.expect(temp.inputStream()).andReturn(wrappedStream())
    }

    whenExecuting(resetHelper, bufferManager, temp) {
      val stream = new SourceStreamWrapper(resetHelper, wrappedStream(),
        Text.length(), bufferManager)
      stream.read(buffer)
      stream.read()
      stream.reset()
      stream.read(buffer)
      stream.read()
    }
    assertEquals("Wrong number of stream read events", 1, streamReadBuilder.size)
  }

  /**
   * Tests a close operation which includes the underlying stream.
   */
  @Test def testCloseCurrentStream() {
    val helper = createResetHelper()
    val stream = new SourceStreamWrapper(helper, wrappedStream(), Text.length,
      bufferManager)
    stream.read()
    assertNotNull("No current stream", stream.currentStream)
    stream.closeCurrentStream()
    assertNull("Still got a current stream", stream.currentStream)
    assertTrue("Helper not closed", helper.closed)
  }

  /**
   * Tests whether the presence of a stream is checked before it gets closed.
   */
  @Test def testCloseCurrentStreamMulti() {
    val helper = createResetHelper()
    val stream = new SourceStreamWrapper(helper, wrappedStream(), Text.length,
      bufferManager)
    stream.read()
    stream.close()
    stream.close()
  }

  /**
   * Tests that a plain close() does not close the underlying stream.
   */
  @Test def testClose() {
    val helper = createResetHelper()
    val stream = new SourceStreamWrapper(helper, wrappedStream(), Text.length,
      bufferManager)
    stream.read()
    stream.close()
    assertNotNull("Current stream was closed", stream.currentStream)
    assertTrue("Helper not closed", helper.closed)
  }

  /**
   * A specialized reset helper implementation which can be used to verify that
   * the helper was closed.
   */
  private class MyResetHelper(factory: TempFileFactory)
    extends StreamResetHelper(factory) {
    var closed: Boolean = false

    override def close() {
      super.close()
      closed = true
    }
  }

  /**
   * A specialized answer implementation which appends its parameter to a list
   * buffer.
   * @param buffer the buffer
   */
  private class ListAppendAnswer(buffer: ListBuffer[Long]) extends IAnswer[Object] {
    def answer() = {
      val value = EasyMock.getCurrentArguments()(0).asInstanceOf[Long]
      buffer += value
      null
    }
  }
}
