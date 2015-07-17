package de.oliver_heger.splaya.mp3

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.file.{Files, Paths}

import de.oliver_heger.splaya.FileTestHelper
import org.scalatest.{FlatSpec, Matchers}

object ID3FrameExtractorSpec {
  /**
   * Creates an array for the binary data of an ID3 header. The array is
   * already filled with the expected header bytes.
   * @param size the optional header size (only a single byte)
   * @return the prepared data array
   */
  private def prepareID3HeaderArray(size: Int = 0): Array[Byte] = {
    def b(n: Int) = n.toByte
    val fill = b(0)
    Array(b(0x49), b(0x44), b(0x33), fill, fill, fill, fill, fill, fill, size.toByte)
  }

  /**
   * Writes an ID3v2.2 tag into the given output stream.
   * @param out the output stream
   * @param name the tag name
   * @param content the content as string
   * @param tagSize an optional tag size; if -1, the size is calculated
   * @return the size of this tag including header
   */
  private def writeV2Tag(out: OutputStream, name: String, content: String,
                         tagSize: Int = -1): Int = {
    out.write(name.getBytes)
    val size = if (tagSize >= 0) tagSize else content.length + 1
    out.write(0)
    out.write(size >> 8)
    out.write(size & 0xFF)
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
    val header = prepareID3HeaderArray(size)
    header(3) = 2
    header
  }

  /**
   * Creates test data for an ID3v2.2 frame. Both the header and the binary
   * data are returned.
   * @return the data
   */
  private def createV2Data(): (ID3Header, Array[Byte]) = {
    val tagsOut = new ByteArrayOutputStream
    var size = 0
    size += writeV2Tag(tagsOut, "TT2", "Tit")
    size += writeV2Tag(tagsOut, "TP1", "Art")
    size += writeV2Tag(tagsOut, "TAL", "Alb")
    size += writeV2Tag(tagsOut, "TYE", "2012")
    size += writeV2Tag(tagsOut, "TRK", "1")
    (ID3Header(2, size), tagsOut.toByteArray)
  }
}

/**
 * Test class for ''ID3FrameExtract''.
 */
class ID3FrameExtractorSpec extends FlatSpec with Matchers {

  import ID3FrameExtractorSpec._

  /**
   * Checks the content of a specific ID3 tag.
   * @param frame the whole ID3 frame
   * @param tagName the name of the tag in question
   * @param exp the expected content of this tag
   */
  private def checkID3Tag(frame: ID3Frame, tagName: String, exp: String) {
    val tag = frame.tags(tagName)
    tag.asString should be(exp)
  }

  /**
   * Reads a file from the test resources and returns its content as an array
   * of bytes.
   * @param name the resource name of the file to be read
   * @return the content of the file
   */
  private def readResourceFile(name: String): Array[Byte] = {
    val fileURI = getClass.getResource("/" + name).toURI
    val path = Paths get fileURI
    Files readAllBytes path
  }

  /**
   * Reads a file from the test resources and parses it using a new frame
   * extractor. The extractor is returned, so that results can be queried.
   * @param name the resource name of the file to be read
   * @return the ''ID3FrameExtractor''
   */
  private def extractorFromResourceFile(name: String): ID3FrameExtractor = {
    val content = readResourceFile(name)
    val headerExtractor = new ID3HeaderExtractor
    val optHeader = headerExtractor extractID3Header content
    optHeader shouldBe 'defined

    val frameData = content.slice(ID3HeaderExtractor.ID3HeaderSize, ID3HeaderExtractor.ID3HeaderSize
      + optHeader.get.size)
    val extractor = new ID3FrameExtractor(optHeader.get)
    extractor addData frameData
  }

  "An ID3FrameExtractor" should "be able to process an ID3v2.2 frame" in {
    val (header, data) = createV2Data()
    val extractor = new ID3FrameExtractor(header)

    extractor addData data
    val frame = extractor.createFrame()
    checkID3Tag(frame, "TT2", "Tit")
    checkID3Tag(frame, "TP1", "Art")
    checkID3Tag(frame, "TAL", "Alb")
    checkID3Tag(frame, "TYE", "2012")
    checkID3Tag(frame, "TRK", "1")
  }

  it should "handle truncated frames" in {
    val out = new ByteArrayOutputStream
    writeV2Tag(out, "TT2", "X")
    val extractor = new ID3FrameExtractor(ID3Header(version = 2, size = 128))

    extractor addData out.toByteArray
    val frame = extractor.createFrame()
    checkID3Tag(frame, "TT2", "X")
  }

  it should "handle truncated tags" in {
    val out = new ByteArrayOutputStream
    writeV2Tag(out, "TT2", "X", 250)
    val extractor = new ID3FrameExtractor(ID3Header(version = 2, size = 250))

    extractor addData out.toByteArray
    val frame = extractor.createFrame()
    checkID3Tag(frame, "TT2", "X")
  }

  it should "deal with an unsupported ID3 version" in {
    val frameSize = 100
    val header = ID3Header(size = frameSize, version = 127)
    val extractor = new ID3FrameExtractor(header)
    val data = new Array[Byte](frameSize)

    extractor addData data
    val frame = extractor.createFrame()
    frame.tags shouldBe 'empty
  }

  it should "be able to process an ID3v2.3 frame" in {
    val extractor = extractorFromResourceFile("test.mp3")
    val frame = extractor.createFrame()
    checkID3Tag(frame, "TIT2", "Testtitle")
    checkID3Tag(frame, "TPE1", "Testinterpret")
    checkID3Tag(frame, "TALB", "A Test Collection")
    checkID3Tag(frame, "TYER", "2006")
    checkID3Tag(frame, "TRCK", "01/10")
  }

  it should "be able to process an ID3v2.4 frame" in {
    val extractor = extractorFromResourceFile("testMP3id3v24.mp3")
    val frame = extractor.createFrame()
    checkID3Tag(frame, "TIT2", "Test Title")
    checkID3Tag(frame, "TPE1", "Test Artist")
    checkID3Tag(frame, "TALB", "Test Album")
    checkID3Tag(frame, "TRCK", "11")
    checkID3Tag(frame, "TCOM", "Harry Hirsch")
    checkID3Tag(frame, "TPE2", "Test Band")
  }

  it should "create an ID3TagProvider for an ID3v2.2 frame" in {
    val (header, data) = createV2Data()
    val extractor = new ID3FrameExtractor(header)
    extractor addData data

    val provider = extractor.createTagProvider().get
    provider.title.get should be("Tit")
    provider.artist.get should be("Art")
    provider.album.get should be("Alb")
    provider.inceptionYear.get should be(2012)
    provider.trackNo.get should be(1)
  }

  it should "create an ID3TagProvider for an ID3v2.3 frame" in {
    val extractor = extractorFromResourceFile("test.mp3")

    val provider = extractor.createTagProvider().get
    provider.title.get should be("Testtitle")
    provider.artist.get should be("Testinterpret")
    provider.album.get should be("A Test Collection")
    provider.inceptionYear.get should be(2006)
    provider.trackNo.get should be(1)
  }

  it should "create an ID3TagProvider for an ID3v2.4 frame" in {
    val extractor = extractorFromResourceFile("testMP3id3v24.mp3")

    val provider = extractor.createTagProvider().get
    provider.title.get should be("Test Title")
    provider.artist.get should be("Test Artist")
    provider.album.get should be("Test Album")
    provider.trackNo.get should be(11)
    provider.inceptionYear shouldBe 'empty
  }

  it should "be able to process frame data chunk-wise" in {
    val (header, data) = createV2Data()
    val extractor = new ID3FrameExtractor(header)

    data grouped (data.length / 3) foreach (extractor.addData(_, complete = false))
    val frame = extractor.createFrame()
    checkID3Tag(frame, "TT2", "Tit")
    checkID3Tag(frame, "TP1", "Art")
    checkID3Tag(frame, "TAL", "Alb")
    checkID3Tag(frame, "TYE", "2012")
    checkID3Tag(frame, "TRK", "1")
  }

  /**
   * Adds the content of the given output stream to the extractor in single
   * chunks of the specified size.
   * @param out the output stream
   * @param chunkSize the chunk size
   * @param extractor the extractor
   */
  private def appendChunkWise(out: ByteArrayOutputStream, chunkSize: Int, extractor:
  ID3FrameExtractor): Unit = {
    val chunks = out.toByteArray.grouped(chunkSize).toList
    chunks dropRight 1 foreach (extractor.addData(_, complete = false))
    extractor addData chunks.last
  }

  it should "handle large tags spanning multiple chunks" in {
    val out = new ByteArrayOutputStream
    val size = writeV2Tag(out, "tst", FileTestHelper.TestData)
    val header = ID3Header(size = size, version = 2)
    val extractor = new ID3FrameExtractor(header)

    appendChunkWise(out, 32, extractor)
    checkID3Tag(extractor.createFrame(), "tst", FileTestHelper.TestData)
  }

  it should "process the last chunk of a large tag even if truncated" in {
    val out = new ByteArrayOutputStream
    val size = writeV2Tag(out, "tst", FileTestHelper.TestData)
    val header = ID3Header(size = size, version = 2)
    val chunks = out.toByteArray.grouped(32).toList
    val completeChunks = chunks dropRight 1
    val extractor = new ID3FrameExtractor(header)

    completeChunks foreach (extractor.addData(_, complete = false))
    val lastChunk = chunks.last
    extractor addData lastChunk.dropRight(1)
    // the trailing 0 was dropped, so the text is complete
    checkID3Tag(extractor.createFrame(), "tst", FileTestHelper.TestData)
  }

  it should "skip a tag in a single chunk if it is too big" in {
    val out = new ByteArrayOutputStream
    var size = writeV2Tag(out, "tg1", "tag1")
    size += writeV2Tag(out, "skp", "This is tag with a long content!")
    size += writeV2Tag(out, "tg2", "tag2")
    val extractor = new ID3FrameExtractor(ID3Header(version = 2, size = size), 5)
    extractor addData out.toByteArray

    val frame = extractor.createFrame()
    checkID3Tag(frame, "tg1", "tag1")
    checkID3Tag(frame, "tg2", "tag2")
    frame.tags.contains("skp") shouldBe false
  }

  it should "skip a tag at the end of a chunk if it is too big" in {
    val out = new ByteArrayOutputStream
    var size = writeV2Tag(out, "tg1", "tag1")
    size += writeV2Tag(out, "skp", "This is tag with a long content!")
    val extractor = new ID3FrameExtractor(ID3Header(version = 2, size = size), 5)
    extractor addData out.toByteArray

    val frame = extractor.createFrame()
    checkID3Tag(frame, "tg1", "tag1")
    frame.tags.contains("skp") shouldBe false
  }

  it should "skip a tag over multiple chunks if it is too big" in {
    val out = new ByteArrayOutputStream
    var size = writeV2Tag(out, "tg1", "tag1")
    size += writeV2Tag(out, "skp", FileTestHelper.TestData)
    size += writeV2Tag(out, "tg2", "tag2")
    val extractor = new ID3FrameExtractor(ID3Header(version = 2, size = size), 5)
    appendChunkWise(out, 64, extractor)

    val frame = extractor.createFrame()
    checkID3Tag(frame, "tg1", "tag1")
    checkID3Tag(frame, "tg2", "tag2")
    frame.tags.contains("skp") shouldBe false
  }

  it should "skip the last tag over multiple chunks if it is too big" in {
    val out = new ByteArrayOutputStream
    var size = writeV2Tag(out, "tg1", "tag1")
    size += writeV2Tag(out, "skp", FileTestHelper.TestData)
    val extractor = new ID3FrameExtractor(ID3Header(version = 2, size = size), 5)
    appendChunkWise(out, 64, extractor)

    val frame = extractor.createFrame()
    checkID3Tag(frame, "tg1", "tag1")
    frame.tags.contains("skp") shouldBe false
  }
}
