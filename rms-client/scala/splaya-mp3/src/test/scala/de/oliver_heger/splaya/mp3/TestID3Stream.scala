package de.oliver_heger.splaya.mp3

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Arrays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.scalatest.junit.JUnitSuite

import de.oliver_heger.tsthlp.StreamDataGenerator

/**
 * Test class for ''ID3Stream''.
 */
class TestID3Stream extends JUnitSuite {
  /** The generator for stream data. */
  private lazy val streamgen = StreamDataGenerator()

  /** The test stream. */
  private var stream: ID3Stream = _

  /**
   * Closes the test stream if necessary.
   */
  @After def tearDown() {
    if (stream != null) {
      stream.close()
    }
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
    stream = new ID3Stream(streamgen.nextStream(256))
    assert(0xFFFFFFF === stream.id3Size(header))
  }

  /**
   * Tests whether a stream can be skipped which does not contain a header.
   */
  @Test def testSkipID3NoHeader() {
    val count = 1024
    val in = streamgen.nextStream(count)
    stream = new ID3Stream(in)
    assertEquals("Wrong result", 0, stream.skipID3())
    val data = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(0, count) === data)
  }

  /**
   * Creates a test ID3 header with the specified size.
   * @param the header size (can only be a single byte)
   * @return the bytes of the test header
   */
  private def createID3Header(headerSize: Int): Array[Byte] = {
    val header = new Array[Byte](10)
    header(0) = 0x49
    header(1) = 0x44
    header(2) = 0x33
    header(9) = headerSize.toByte
    header
  }

  /**
   * Tests whether a stream can be skipped if there is an ID3 header.
   */
  @Test def testSkipID3WithHeader() {
    val count = 1024
    val headerSize = 24
    val header = createID3Header(headerSize)
    val out = new ByteArrayOutputStream
    out.write(header)
    out.write(streamgen.generateStreamContent(0, count).getBytes)
    val in = new ByteArrayInputStream(out.toByteArray)
    stream = new ID3Stream(in)
    assertEquals("Wrong result", 1, stream.skipID3())
    val data = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(headerSize, count - headerSize) === data)
  }

  /**
   * Tests whether multiple ID3 headers can be skipped.
   */
  @Test def testSkipID3WithMultipleHeaders() {
    val out = new ByteArrayOutputStream
    val id3Size1 = 32
    out.write(createID3Header(id3Size1))
    out.write(streamgen.generateStreamContent(0, id3Size1).getBytes)
    val id3Size2 = 64
    out.write(createID3Header(id3Size2))
    out.write(streamgen.generateStreamContent(id3Size1, id3Size2).getBytes)
    val contentSize = 128
    out.write(streamgen.generateStreamContent(id3Size1 + id3Size2,
      contentSize).getBytes)
    val in = new ByteArrayInputStream(out.toByteArray)
    stream = new ID3Stream(in)
    assertEquals("Wrong result", 2, stream.skipID3())
    val data = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(id3Size1 + id3Size2, contentSize) === data)
  }

  /**
   * Tests an input stream whose size is shorter than an ID3 header.
   */
  @Test def testSkipID3TooShort() {
    val count = 5
    stream = new ID3Stream(streamgen.nextStream(count))
    assertEquals("Wrong result", 0, stream.skipID3())
    val data = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(0, count) === data)
  }

  /**
   * Tests isID3Header() if the passed in array is too short.
   */
  @Test def testIsID3HeaderArrayTooShort() {
    val arr = new Array[Byte](1)
    assertFalse("Wrong result", ID3Stream.isID3Header(arr))
  }

  /**
   * Creates a stream for the given test file.
   * @param name the name of the test file
   */
  private def streamForTestFile(name: String): ID3Stream =
    new ID3Stream(getClass.getResourceAsStream("/" + name))

  /**
   * Checks the content of a specific tag.
   * @param frame the whole ID3 frame
   * @param tagName the name of the tag in question
   * @param exp the expected content of this tag
   */
  private def checkID3Tag(frame: ID3Frame, tagName: String, exp: String) {
    val tag = frame.tags(tagName)
    assert(exp === tag.asString)
  }

  /**
   * Tests whether a file with ID3v2.3 tags can be read.
   */
  @Test def testReadID3v3() {
    stream = streamForTestFile("test.mp3")
    val frameOpt = stream.nextID3Frame()
    assertTrue("Undefined", frameOpt.isDefined)
    val frame = frameOpt.get
    checkID3Tag(frame, "TIT2", "Testtitle")
    checkID3Tag(frame, "TPE1", "Testinterpret")
    checkID3Tag(frame, "TALB", "A Test Collection")
    checkID3Tag(frame, "TYER", "2006")
    checkID3Tag(frame, "TRCK", "01/10")
    assertFalse("Got another frame", stream.nextID3Frame().isDefined)
  }

  /**
   * Tests whether a file with ID3v2.4 tags can be read.
   */
  @Test def testReadID3v4() {
    stream = streamForTestFile("testMP3id3v24.mp3")
    val frameOpt = stream.nextID3Frame()
    assertTrue("Undefined", frameOpt.isDefined)
    val frame = frameOpt.get
    checkID3Tag(frame, "TIT2", "Test Title")
    checkID3Tag(frame, "TPE1", "Test Artist")
    checkID3Tag(frame, "TALB", "Test Album")
    checkID3Tag(frame, "TRCK", "11")
    checkID3Tag(frame, "TCOM", "Harry Hirsch")
    checkID3Tag(frame, "TPE2", "Test Band")
    assertFalse("Got another frame", stream.nextID3Frame().isDefined)
  }

  /**
   * Writes an ID3v2.2 tag into the given output stream.
   * @param out the output stream
   * @param name the tag name
   * @param the content as string
   * @param tagSize an optional tag size; if -1, the size is calculated
   * @return the size of this tag including header
   */
  private def writeV2Tag(out: OutputStream, name: String, content: String,
    tagSize: Int = -1): Int = {
    out.write(name.getBytes)
    val size = content.length + 1
    out.write(0)
    out.write(0)
    out.write(if (tagSize >= 0) tagSize else size)
    out.write(content.getBytes)
    out.write(0)
    size + 6
  }

  /**
   * Creates a header for an ID3v2.2 frame.
   * @param size the size of the frame
   * @return the array with the content of the header
   */
  private def createV2Header(size: Int): Array[Byte] = {
    val header = createID3Header(size)
    header(3) = 2
    header
  }

  /**
   * Creates an ID3Stream on an input stream with an ID3v2.2 frame.
   * @return the ID3Stream
   */
  private def createV2Stream(): ID3Stream = {
    val tagsOut = new ByteArrayOutputStream
    var size = 0
    size += writeV2Tag(tagsOut, "TT2", "Tit")
    size += writeV2Tag(tagsOut, "TP1", "Art")
    size += writeV2Tag(tagsOut, "TAL", "Alb")
    size += writeV2Tag(tagsOut, "TYE", "2012")
    size += writeV2Tag(tagsOut, "TRK", "1")
    val out = new ByteArrayOutputStream
    val header = createV2Header(size)
    out.write(header)
    out.write(tagsOut.toByteArray)
    val in = new ByteArrayInputStream(out.toByteArray)
    new ID3Stream(in)
  }

  /**
   * Tests whether ID3v2.2 tags can be read.
   */
  @Test def testReadID3v2() {
    stream = createV2Stream()
    val frameOpt = stream.nextID3Frame()
    assertTrue("Undefined", frameOpt.isDefined)
    val frame = frameOpt.get
    checkID3Tag(frame, "TT2", "Tit")
    checkID3Tag(frame, "TP1", "Art")
    checkID3Tag(frame, "TAL", "Alb")
    checkID3Tag(frame, "TYE", "2012")
    checkID3Tag(frame, "TRK", "1")
  }

  /**
   * Tests whether missing ID3 information is handled correctly.
   */
  @Test def testNextFrameNoID3Data() {
    stream = streamForTestFile("test2.mp3")
    assertFalse("Got a frame", stream.nextID3Frame().isDefined)
  }

  /**
   * Tests whether a truncated ID3v2 frame is handled correctly.
   */
  @Test def testNextFrameTruncated() {
    val out = new ByteArrayOutputStream
    out.write(createV2Header(127))
    writeV2Tag(out, "TT2", "X")
    stream = new ID3Stream(new ByteArrayInputStream(out.toByteArray))
    val frame = stream.nextID3Frame().get
    checkID3Tag(frame, "TT2", "X")
  }

  /**
   * Tests whether a truncated tag is handled correctly.
   */
  @Test def testNextFrameTagTruncated() {
    val out = new ByteArrayOutputStream
    out.write(createV2Header(127))
    writeV2Tag(out, "TT2", "X", 250)
    stream = new ID3Stream(new ByteArrayInputStream(out.toByteArray))
    val frame = stream.nextID3Frame().get
    checkID3Tag(frame, "TT2", "X")
  }

  /**
   * Tests whether an unsupported ID3v2 version is handled correctly. In this
   * case, the frame is just skipped, and a frame object without data is
   * returned.
   */
  @Test def testNextFrameUnsupportedVersion() {
    val frameSize = 100
    val size = frameSize + 64
    val header = createID3Header(frameSize)
    header(3) = 127
    val out = new ByteArrayOutputStream
    out.write(header)
    out.write(streamgen.generateStreamContent(0, size).getBytes)
    stream = new ID3Stream(new ByteArrayInputStream(out.toByteArray))
    val frame = stream.nextID3Frame().get
    assertEquals("Wrong version", 127, frame.header.version)
    assertTrue("Got tags", frame.tags.isEmpty)
  }

  /**
   * Tests whether the content of a tag can be obtained as a byte array.
   */
  @Test def testTagContentAsArray() {
    stream = streamForTestFile("test.mp3")
    val frame = stream.nextID3Frame().get
    val tag = frame.tags("TIT2")
    val data = tag.asBytes
    val data2 = tag.asBytes
    assertNotSame("Same data array", data, data2)
    assertTrue("Different content", Arrays.equals(data, data2))
  }

  /**
   * Tests whether a string can be obtained from a tag which has no content.
   */
  @Test def testAsStringNoLength() {
    val tag = ID3Tag("TAG", Array.empty)
    assert("" === tag.asString)
  }

  /**
   * Tests asString() if the tag only contains a null byte.
   */
  @Test def testAsStringNullOnly() {
    val tag = ID3Tag("TAG", Array(0))
    assert("" === tag.asString)
  }

  /**
   * Tests asString() if no encoding byte is present.
   */
  @Test def testAsStringNoEncoding() {
    val text = "This is a test!"
    val tag = ID3Tag("Tag", text.getBytes)
    assert(text === tag.asString)
  }

  /**
   * Tests whether the underlying stream is at the correct position after
   * reading the ID3 block even if there is padding.
   */
  @Test def testReadID3CorrectSkip() {
    val count = 128
    val padding = 4
    val tagsOut = new ByteArrayOutputStream
    var size = 0
    size += writeV2Tag(tagsOut, "TT2", "Title")
    val out = new ByteArrayOutputStream
    val header = createV2Header(size + padding)
    out.write(header)
    out.write(tagsOut.toByteArray)
    out.write(streamgen.generateStreamContent(0, count).getBytes)
    val in = new ByteArrayInputStream(out.toByteArray)
    stream = new ID3Stream(in)
    assertTrue("Not defined", stream.nextID3Frame().isDefined)
    val content = new String(StreamDataGenerator.readStream(stream))
    assert(streamgen.generateStreamContent(padding, count - padding) === content)
  }

  /**
   * Tries to obtain an ID3 tag provider for an unsupported version.
   */
  @Test(expected = classOf[NoSuchElementException])
  def testProviderForUnsupportedVersion() {
    val header = ID3Header(size = 128, version = 20121014)
    val frame = ID3Frame(header = header, tags = Map.empty)
    ID3Stream.providerFor(frame)
  }

  /**
   * Tests a tag provider for an ID3v2.2 frame.
   */
  @Test def testProviderForV2Frame() {
    val stream = createV2Stream()
    val frame = stream.nextID3Frame().get
    val provider = ID3Stream providerFor frame
    assertEquals("Wrong title", "Tit", provider.title.get)
    assertEquals("Wrong artist", "Art", provider.artist.get)
    assertEquals("Wrong album", "Alb", provider.album.get)
    assertEquals("Wrong year", 2012, provider.inceptionYear.get)
    assertEquals("Wrong track", 1, provider.trackNo.get)
  }

  /**
   * Tests a tag provider for an ID3v2.3 frame.
   */
  @Test def testProviderForV3Frame() {
    stream = streamForTestFile("test.mp3")
    val frame = stream.nextID3Frame().get
    val provider = ID3Stream providerFor frame
    assertEquals("Wrong title", "Testtitle", provider.title.get)
    assertEquals("Wrong artist", "Testinterpret", provider.artist.get)
    assertEquals("Wrong album", "A Test Collection", provider.album.get)
    assertEquals("Wrong year", 2006, provider.inceptionYear.get)
    assertEquals("Wrong track", 1, provider.trackNo.get)
  }

  /**
   * Tests a tag provider for an ID3v2.4 frame.
   */
  @Test def testProviderForV4Frame() {
    stream = streamForTestFile("testMP3id3v24.mp3")
    val frame = stream.nextID3Frame().get
    val provider = ID3Stream providerFor frame
    assertEquals("Wrong title", "Test Title", provider.title.get)
    assertEquals("Wrong artist", "Test Artist", provider.artist.get)
    assertEquals("Wrong album", "Test Album", provider.album.get)
    assertEquals("Wrong track", 11, provider.trackNo.get)
    assertFalse("Got a year", provider.inceptionYear.isDefined)
  }
}
