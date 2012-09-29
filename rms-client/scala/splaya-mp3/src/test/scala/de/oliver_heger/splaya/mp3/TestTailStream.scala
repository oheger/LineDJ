package de.oliver_heger.splaya.mp3

import org.scalatest.junit.JUnitSuite
import de.oliver_heger.tsthlp.StreamDataGenerator
import org.junit.Before
import org.junit.Test
import org.junit.Assert._
import java.util.Arrays

/**
 * Test class for ''TailStream''.
 */
class TestTailStream extends JUnitSuite {
  /** The stream generator. */
  private var streamgen: StreamDataGenerator = _

  @Before def setUp() {
    streamgen = StreamDataGenerator()
  }

  /**
   * Tests whether the tail buffer can be obtained before data was read.
   */
  @Test def testTailBeforeRead() {
    val stream = new TailStream(streamgen.nextStream(100), 50)
    assert(0 === stream.tail.length)
  }

  /**
   * Tests the content of the tail buffer if it is only partly filled.
   */
  @Test def testTailBufferPartlyRead() {
    val count = 64
    val stream = new TailStream(streamgen.nextStream(count), 2 * count)
    val data = StreamDataGenerator.readStream(stream)
    assertTrue("Wrong content", Arrays.equals(data, stream.tail))
  }

  /**
   * Tests the content of the tail buffer if only single bytes were read.
   */
  @Test def testTailSingleByteReads() {
    val count = 128
    val stream = new TailStream(streamgen.nextStream(2 * count), count)
    var c = stream.read()
    while (c != -1) {
      c = stream.read()
    }
    assert(streamgen.generateStreamContent(count, count) === new String(stream.tail))
  }

  /**
   * Tests the content of the tail buffer if larger chunks are read.
   */
  @Test def testTailChunkReads() {
    val count = 16384
    val tailSize = 61
    val bufSize = 100
    val stream = new TailStream(streamgen.nextStream(count), tailSize)
    val buf = new Array[Byte](bufSize)
    var read = stream.read(buf, 10, 8)
    assertEquals("Wrong number of bytes read", 8, read)
    while (read != -1) {
      read = stream.read(buf)
    }
    assert(streamgen.generateStreamContent(count - tailSize, tailSize)
      === new String(stream.tail))
  }

  /**
   * Tests whether mark() and reset() work as expected.
   */
  @Test def testReadWithMarkAndReset() {
    val tailSize = 64
    val stream = new TailStream(streamgen.nextStream(2 * tailSize), tailSize)
    val buf = new Array[Byte](tailSize / 2)
    stream.read(buf)
    stream.mark(tailSize)
    stream.read(buf)
    stream.reset()
    StreamDataGenerator.readStream(stream)
    assert(streamgen.generateStreamContent(tailSize, tailSize)
      === new String(stream.tail))
  }

  /**
   * Tests whether a reset() operation without a mark is simply ignored.
   */
  @Test def testResetWithoutMark() {
    val tailSize = 75
    val count = 128
    val stream = new TailStream(streamgen.nextStream(count), tailSize)
    stream.reset()
    val buf = new Array[Byte](count)
    stream.read(buf)
    assert(streamgen.generateStreamContent(count - tailSize, tailSize)
      === new String(stream.tail))
  }
}
