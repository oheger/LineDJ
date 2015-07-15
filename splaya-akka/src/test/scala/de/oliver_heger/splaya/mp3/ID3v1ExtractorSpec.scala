/*
 * Copyright 2015 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.oliver_heger.splaya.mp3

import java.nio.file.{Files, Paths}

import de.oliver_heger.splaya.FileTestHelper
import de.oliver_heger.splaya.io.ChannelHandler.ArraySource
import de.oliver_heger.splaya.io.DynamicInputStream
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

object ID3v1ExtractorSpec {
  /**
   * Writes the given text byte-wise into the specified array. This method can
   * be used to populate an array which can then be processed by the extractor.
   * @param buf the array to be filled
   * @param txt the text to write
   * @param pos the optional start position
   * @return the array
   */
  private def fillArray(buf: Array[Byte], txt: String, pos: Int = 0): Array[Byte] = {
    val txtBytes = FileTestHelper toBytes txt
    System.arraycopy(txtBytes, 0, buf, pos, txtBytes.length)
    buf
  }
}

/**
 * Test class for ''ID3v1Extractor''.
 */
class ID3v1ExtractorSpec extends FlatSpec with Matchers with MockitoSugar {

  import ID3v1ExtractorSpec._

  "An ID3v1Extractor" should "create a correct tail buffer" in {
    val extractor = new ID3v1Extractor

    extractor.tailBuffer.size should be(128)
  }

  it should "pass chunks of data to the tail buffer" in {
    val buffer = mock[TailBuffer]
    val src = mock[ArraySource]
    val extractor = new ID3v1Extractor(buffer)

    extractor addData src should be(extractor)
    verify(buffer).addData(src)
  }

  /**
   * Creates an extractor object with a mock tail buffer that is prepared to
   * return the specified data.
   * @param data the data to be returned by the tail buffer
   * @return the extractor
   */
  private def prepareExtractorWithData(data: Array[Byte]): ID3v1Extractor = {
    val buffer = mock[TailBuffer]
    when(buffer.tail()).thenReturn(data)
    new ID3v1Extractor(buffer)
  }

  it should "reject an empty frame" in {
    val extractor = prepareExtractorWithData(Array.emptyByteArray)

    extractor.createTagProvider() shouldBe 'empty
  }

  it should "reject a frame which is too small" in {
    val data = new Array[Byte](127)
    val extractor = prepareExtractorWithData(fillArray(data, "TAG"))

    extractor.createTagProvider() shouldBe 'empty
  }

  it should "reject a frame with no valid ID" in {
    val data = new Array[Byte](128)
    val extractor = prepareExtractorWithData(fillArray(data, "TAJ"))

    extractor.createTagProvider() shouldBe 'empty
  }

  /**
   * Reads the content of the given test file and adds it to a newly created
   * extractor. It can then be checked whether the correct data was extracted.
   * @param fileName the name of the test file
   * @return the populated extractor
   */
  private def extractorForFile(fileName: String): ID3v1Extractor = {
    val uri = getClass.getResource("/" + fileName)
    val path = Paths get uri.toURI
    val content = Files readAllBytes path
    val extractor = new ID3v1Extractor
    extractor.addData(DynamicInputStream arraySourceFor content)
  }

  it should "extract data from a valid frame" in {
    val extractor = extractorForFile("test.mp3")
    val provider = extractor.createTagProvider().get

    provider.artist.get should be("Testinterpret")
    provider.title.get should be("Testtitle")
    provider.album.get should be("A Test Collection")
    provider.inceptionYear.get should be(2006)
    provider.trackNo shouldBe 'empty
  }

  it should "extract the track number if defined" in {
    val extractor = extractorForFile("testMP3id3v1.mp3")
    val provider = extractor.createTagProvider().get

    provider.title.get should be("Test Title")
    provider.trackNo.get should be(1)
  }

  /**
   * Creates an extractor and prepares it for a test about string extraction.
   * A valid frame is created with the given string written to the title
   * field. Then a provider is created.
   * @param txt the text
   * @return the tag provider
   */
  private def providerFromStringExtractionTest(txt: String): ID3TagProvider = {
    val data = new Array[Byte](128)
    fillArray(data, "TAG")
    fillArray(data, txt, 3)
    prepareExtractorWithData(data).createTagProvider().get
  }

  it should "ignore a string consisting only of 0 bytes" in {
    val provider = providerFromStringExtractionTest("")

    provider.title shouldBe 'empty
  }

  it should "ignore a string consisting only of whitespace" in {
    val provider = providerFromStringExtractionTest(" " * 32)

    provider.title shouldBe 'empty
  }

  it should "trim text data" in {
    val provider = providerFromStringExtractionTest(" Leading +   trailing Space!!   ")

    provider.title.get should be("Leading +   trailing Space!!")
  }
}
