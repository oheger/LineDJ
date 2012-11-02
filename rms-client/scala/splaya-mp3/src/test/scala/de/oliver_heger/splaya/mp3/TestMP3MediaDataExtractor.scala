package de.oliver_heger.splaya.mp3

import java.io.InputStream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.scalatest.junit.JUnitSuite

/**
 * Test class for ''ID3MediaDataExtractor''.
 */
class TestMP3MediaDataExtractor extends JUnitSuite {
  /** The extractor to be tested. */
  private var extractor: MP3MediaDataExtractor = _

  @Before def setUp() {
    extractor = new MP3MediaDataExtractor
  }

  /**
   * Creates a stream for the given test file.
   * @param name the name of the test file
   * @return a stream for this test file
   */
  private def streamForTestFile(name: String): InputStream =
    getClass.getResourceAsStream("/" + name)

  /**
   * Tests whether data from an MP3 file with ID3v2 tags can be extracted.
   */
  @Test def testExtractDataV2() {
    val optData = extractor.extractData(streamForTestFile("test.mp3"))
    assertTrue("Not defined", optData.isDefined)
    val data = optData.get
    assertEquals("Wrong title", "Testtitle", data.title)
    assertEquals("Wrong artist", "Testinterpret", data.artistName)
    assertEquals("Wrong album", "A Test Collection", data.albumName)
    assertEquals("Wrong year", 2006, data.inceptionYear)
    assertEquals("Wrong track", 1, data.trackNo)
    assertEquals("Wrong duration", 10, data.duration / 1000)
  }

  /**
   * Tests whether data from an MP3 file with ID3v1 tags can be extracted.
   */
  @Test def testExtractDataV1() {
    val optData = extractor.extractData(streamForTestFile("testMP3id3v1.mp3"))
    assertTrue("Not defined", optData.isDefined)
    val data = optData.get
    assertEquals("Wrong title", "Test Title", data.title)
    assertEquals("Wrong artist", "Test Artist", data.artistName)
    assertEquals("Wrong album", "Test Album", data.albumName)
    assertEquals("Wrong year", 2008, data.inceptionYear)
    assertEquals("Wrong track", 1, data.trackNo)
    assertEquals("Wrong duration", 2, data.duration / 1000)
  }

  /**
   * Tests whether data from an MP3 file with both ID3v2 and ID3v1 tags can be
   * extracted.
   */
  @Test def testExtractDataV2andV1() {
    val optData = extractor.extractData(streamForTestFile("testMultiID3Frames.mp3"))
    assertTrue("Not defined", optData.isDefined)
    val data = optData.get
    assertEquals("Wrong title", "Title_v2", data.title)
    assertEquals("Wrong artist", "Artist_v1", data.artistName)
    assertEquals("Wrong album", "Album_v2", data.albumName)
    assertEquals("Wrong year", 2002, data.inceptionYear)
    assertEquals("Wrong track", 1, data.trackNo)
    assertEquals("Wrong duration", 6, data.duration / 1000)
  }

  /**
   * Tests an MP3 file without ID3 information. At least the duration should
   * be extracted.
   */
  @Test def testExtractDataNoID3Frames() {
    val optData = extractor.extractData(streamForTestFile("test2.mp3"))
    assertTrue("Not defined", optData.isDefined)
    val data = optData.get
    assertNull("Got a title", data.title)
    assertNull("Got an artist", data.artistName)
    assertNull("Got an album", data.albumName)
    assertEquals("Got a year", 0, data.inceptionYear)
    assertEquals("Got a track", 0, data.trackNo)
    assertEquals("Wrong duration", 6, data.duration / 1000)
  }

  /**
   * Tests an extract operation if the target file is not an MP3 file.
   */
  @Test def testExtractDataNoMP3() {
    val optData = extractor.extractData(streamForTestFile("test.wav"))
    assertFalse("Got data", optData.isDefined)
  }

  /**
   * Tests whether a number of frames is correctly sorted.
   */
  @Test def testSortFrames() {
    val tags = Map.empty[String, ID3Tag]
    val frameV2 = new ID3Frame(new ID3Header(2, 200), tags)
    val frameV3 = new ID3Frame(new ID3Header(3, 300), tags)
    val frameV4 = new ID3Frame(new ID3Header(4, 400), tags)
    val frameList = List(frameV3, frameV4, frameV2)
    assertEquals("Wrong sorted list", List(frameV2, frameV3, frameV4),
      extractor.sortFrames(frameList))
  }
}
