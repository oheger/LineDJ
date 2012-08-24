package de.oliver_heger.splaya.engine.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite

import de.oliver_heger.tsthlp.StreamDataGenerator

/**
 * Test class for ''ID3SkipStream''.
 */
class TestID3SkipStream extends JUnitSuite {
  /** The generator for stream data. */
  private var streamgen: StreamDataGenerator = _

  @Before def setUp() {
    streamgen = StreamDataGenerator()
  }

  /**
   * Tests whether all factors for calculating the size of the ID3 block are
   * correct.
   */
  @Test def testId3SizeFactors() {
    val header = new Array[Byte](10)
    header(6) = 0x7F
    header(7) = 0x7F
    header(8) = 0x7F
    header(9) = 0x7F
    val stream = new ID3SkipStream(streamgen.nextStream(256))
    assert(0xFFFFFFF === stream.id3Size(header))
  }

  /**
   * Tests whether a stream can be skipped which does not contain a header.
   */
  @Test def testSkipID3NoHeader() {
    val count = 1024
    val in = streamgen.nextStream(count)
    val stream = new ID3SkipStream(in)
    assertFalse("Wrong result", stream.skipID3())
    val data = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(0, count) === data)
  }

  /**
   * Tests whether a stream can be skipped if there is an ID3 header.
   */
  @Test def testSkipID3WithHeader() {
    val count = 1024
    val headerSize = 24
    val header = new Array[Byte](10)
    header(0) = 0x49
    header(1) = 0x44
    header(2) = 0x33
    header(9) = headerSize.toByte
    val out = new ByteArrayOutputStream
    out.write(header)
    out.write(streamgen.generateStreamContent(0, count).getBytes)
    val in = new ByteArrayInputStream(out.toByteArray)
    val stream = new ID3SkipStream(in)
    assertTrue("Wrong result", stream.skipID3())
    val data = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(headerSize, count - headerSize) === data)
  }

  /**
   * Tests isID3Header() if the passed in array is too short.
   */
  @Test def testIsID3HeaderArrayTooShort() {
    val arr = new Array[Byte](1)
    assertFalse("Wrong result", ID3SkipStream.isID3Header(arr))
  }
}
