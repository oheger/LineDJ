package de.oliver_heger.splaya.mp3

import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.scalatest.junit.JUnit3Suite

/**
 * Test class for ''MpegStream''.
 */
class TestMpegStream extends JUnit3Suite {
  /** The test stream. */
  private var stream: MpegStream = _

  /**
   * Closes the test stream if it is open.
   */
  @After override def tearDown() {
    if (stream != null) {
      stream.close()
    }
  }

  /**
   * Returns an input stream for the test file with the given name.
   * @param name the name of the test audio file
   * @return an input stream for this file
   */
  private def testStream(name: String): InputStream =
    getClass.getResourceAsStream("/" + name)

  /**
   * Tests whether the default test header can be found in a stream.
   * @param bos the stream
   */
  private def checkDefaultHeader(bos: ByteArrayOutputStream) {
    val in = new ByteArrayInputStream(bos.toByteArray)
    stream = new MpegStream(in)
    val headerOpt = stream.nextFrame()
    assertTrue("No header found", headerOpt.isDefined)
    val header = headerOpt.get
    assertEquals("Wrong MPEG version", MpegHeader.MpegV2, header.mpegVersion)
    assertEquals("Wrong layer", MpegHeader.Layer3, header.layer)
    assertEquals("Wrong bit rate", 80000, header.bitRate)
    assertEquals("Wrong sample rate", 24000, header.sampleRate)
  }

  /**
   * Tests whether an audio frame header can be found somewhere in a stream.
   */
  @Test def testSearchNextFrame() {
    val bos = new ByteArrayOutputStream

    def writeBytes(value: Int, count: Int) {
      for (i <- 0 to count) {
        bos.write(value)
      }
    }

    writeBytes(0xFF, 32)
    writeBytes(0, 16)
    writeBytes(0xFF, 8)
    bos.write(0xF3)
    bos.write(0x96)
    bos.write(0)
    checkDefaultHeader(bos)
  }

  /**
   * Tests whether invalid frame headers are detected and skipped.
   */
  @Test def testSearchNextFrameInvalid() {
    val bos = new ByteArrayOutputStream

    def writeFrame(b2: Int, b3: Int, b4: Int) {
      bos.write(0xFF)
      bos.write(b2)
      bos.write(b3)
      bos.write(b4)
    }

    writeFrame(0xEB, 0x96, 0)
    writeFrame(0xF9, 0x96, 0)
    writeFrame(0xF3, 0, 0)
    writeFrame(0xF3, 0xF0, 0)
    writeFrame(0xF3, 0x7C, 0)
    writeFrame(0xF3, 0x96, 0)
    checkDefaultHeader(bos)
  }

  /**
   * Tests a search for another frame which is interrupted because the stream
   * ends.
   */
  @Test def testSeachNextFrameEOS() {
    val bos = new ByteArrayOutputStream
    bos.write(0xFF)
    bos.write(0xFF)
    bos.write(0xF3)
    bos.write(0x96)
    val in = new ByteArrayInputStream(bos.toByteArray)
    stream = new MpegStream(in)
    assert(None === stream.nextFrame())
  }

  /**
   * Helper method for testing whether the duration of a test file can be
   * determined.
   * @param fileName the name of the test file
   * @param expected the expected duration (in milliseconds)
   */
  private def checkDuration(fileName: String, expected: Int) {
    val id3Stream = new ID3Stream(testStream(fileName))
    id3Stream.skipID3()
    stream = new MpegStream(id3Stream)
    var duration = 0.0f
    var header = stream.nextFrame()
    while (header.isDefined) {
      duration += header.get.duration
      assertTrue("Wrong result", stream.skipFrame())
      header = stream.nextFrame()
    }
    val roundDuration = scala.math.round(duration)
    assertTrue("Wrong duration: " + roundDuration,
      math.abs(expected - roundDuration) < 100)
  }

  /**
   * Tests whether the duration of a test file can be determined (1).
   */
  @Test def testDuration1() {
    checkDuration("test.mp3", 10842)
  }

  /**
   * Tests whether the duration of a test file can be determined (2).
   */
  @Test def testDuration2() {
    checkDuration("test2.mp3", 6734)
  }

  /**
   * Tries to skip a frame if no current header is available.
   */
  @Test def testSkipNoCurrentHeader() {
    stream = new MpegStream(testStream("test.mp3"))
    assertFalse("Wrong result", stream.skipFrame())
  }
}
