package de.oliver_heger.splaya.mp3

import de.oliver_heger.splaya.FileTestHelper
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''ID3DataExtractor''.
 */
class ID3DataExtractorSpec extends FlatSpec with Matchers {
  "An ID3DataExtractor" should "define the correct ID3 header size" in {
    ID3HeaderExtractor.ID3HeaderSize should be(10)
  }

  it should "reject an array which is too small" in {
    val extractor = new ID3HeaderExtractor
    val data = new Array[Byte](ID3HeaderExtractor.ID3HeaderSize - 1)

    extractor extractID3Header data shouldBe 'empty
  }

  it should "reject an array that does not contain header information" in {
    val extractor = new ID3HeaderExtractor
    val data = FileTestHelper.toBytes("ID201234569870")

    extractor extractID3Header data shouldBe 'empty
  }

  /**
   * Creates an array for the binary data of an ID3 header. The array is
   * already filled with the expected header bytes.
   * @return the prepared data array
   */
  private def prepareID3HeaderArray(): Array[Byte] = {
    def b(n: Int) = n.toByte
    val fill = b(0)
    Array(b(0x49), b(0x44), b(0x33), fill, fill, fill, fill, fill, fill, fill)
  }

  it should "create a correct ID3Header object for valid data" in {
    val data = prepareID3HeaderArray()
    data(3) = 2
    data(9) = 42
    val extractor = new ID3HeaderExtractor

    val header = extractor.extractID3Header(data).get
    header.size should be(42)
    header.version should be(2)
  }

  it should "calculate the correct frame size from all components" in {
    val header = prepareID3HeaderArray()
    header(6) = 0x7F
    header(7) = 0x7F
    header(8) = 0x7F
    header(9) = 0x7F
    val extractor = new ID3HeaderExtractor

    val headerObj = extractor.extractID3Header(header).get
    headerObj.size should be(0xFFFFFFF)
  }
}
