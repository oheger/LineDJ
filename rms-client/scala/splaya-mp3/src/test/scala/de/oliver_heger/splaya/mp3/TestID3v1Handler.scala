package de.oliver_heger.splaya.mp3

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.junit.Assert._
import java.util.Arrays

/**
 * Test class for ''ID3v1Handler''.
 */
class TestID3v1Handler extends JUnitSuite {
  /**
   * Tests extractString() if the data is all 0.
   */
  @Test def testExtractStringAll0() {
    val buf = new Array[Byte](32)
    buf(0) = 'a'
    buf(31) = 'z'
    assertFalse("Got a string", ID3v1Handler.extractString(buf, 1, 30).isDefined)
  }

  /**
   * Tests extractString() if the data contains only space.
   */
  @Test def testExtractStringAllBlank() {
    val buf = new Array[Byte](32)
    Arrays.fill(buf, ' '.toByte)
    assertFalse("Got a string", ID3v1Handler.extractString(buf, 0, buf.length).isDefined)
  }

  /**
   * Tests whether a string can be extracted if the whole buffer contains valid
   * characters.
   */
  @Test def testExtractStringFullText() {
    val text = "Test data to be extracted!"
    val buf = text.getBytes
    assert(text === ID3v1Handler.extractString(buf, 0, buf.length).get)
  }

  /**
   * Tests whether leading and trailing whitespace is removed when extracting
   * a string.
   */
  @Test def testExtractStringTrim() {
    val buf = new Array[Byte](256)
    val text = "        Test text with leading  and trailing Space??!     "
    val textBytes = text.getBytes
    System.arraycopy(textBytes, 0, buf, 100, textBytes.length)
    assert(text.trim() === ID3v1Handler.extractString(buf, 100, 100).get)
  }

  /**
   * Tests whether a given provider contains only undefined data.
   * @param provider the provider to test
   */
  private def checkUndefinedProvider(provider: ID3TagProvider) {
    assertFalse("Got a title", provider.title.isDefined)
    assertFalse("Got an artist", provider.artist.isDefined)
    assertFalse("Got an album", provider.album.isDefined)
    assertFalse("Got a year string", provider.inceptionYearString.isDefined)
    assertFalse("Got a year", provider.inceptionYear.isDefined)
    assertFalse("Got a track string", provider.trackNoString.isDefined)
    assertFalse("Got a track number", provider.trackNo.isDefined)
  }

  /**
   * Tests whether an invalid size of the frame buffer is detected.
   */
  @Test def testProviderForWrongBufferSize() {
    val buf = new Array[Byte](ID3v1Handler.FrameSize - 1)
    buf(0) = 'T'
    buf(1) = 'A'
    buf(2) = 'G'
    val provider = ID3v1Handler.providerFor(buf)
    checkUndefinedProvider(provider)
  }

  /**
   * Tests whether a missing header ID is detected.
   */
  @Test def testProviderForNoID() {
    val buf = new Array[Byte](ID3v1Handler.FrameSize)
    buf(0) = 'T'
    buf(1) = 'A'
    buf(2) = 'J'
    val provider = ID3v1Handler.providerFor(buf)
    checkUndefinedProvider(provider)
  }

  /**
   * Reads the ID3v1 information from the given test file. (To be precise, the
   * last 128 Byte chunk is read.)
   */
  private def bufferForTestFile(name: String): Array[Byte] = {
    val stream = new TailStream(getClass.getResourceAsStream("/" + name),
      ID3v1Handler.FrameSize)
    try {
      while (stream.read() != -1) {}
      stream.tail
    } finally {
      stream.close()
    }
  }

  /**
   * Tests whether a frame from an existing file can be read.
   */
  @Test def testProviderForExistingFrame() {
    val provider = ID3v1Handler.providerFor(bufferForTestFile("test.mp3"))
    assertEquals("Wrong artist", "Testinterpret", provider.artist.get)
    assertEquals("Wrong title", "Testtitle", provider.title.get)
    assertEquals("Wrong album", "A Test Collection", provider.album.get)
    assertEquals("Wrong year", 2006, provider.inceptionYear.get)
    assertFalse("Got a track", provider.trackNo.isDefined)
  }

  /**
   * Tests whether a track number can be obtained from an ID3v1.1 frame.
   */
  @Test def testProviderForExistingFrameWithTrack() {
    val provider = ID3v1Handler.providerFor(bufferForTestFile("testMP3id3v1.mp3"))
    assertEquals("Wrong title", "Test Title", provider.title.get)
    assertEquals("Wrong track number", 1, provider.trackNo.get)
  }
}
