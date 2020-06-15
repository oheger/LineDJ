/*
 * Copyright 2015-2020 The Developers Team.
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
package de.oliver_heger.linedj.extract.id3.model

import java.nio.file.{Files, Paths}

import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ID3v1ExtractorSpec {
  /**
    * Writes the given text byte-wise into the specified array. This method can
    * be used to populate an array which can then be processed by the extractor.
    *
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
class ID3v1ExtractorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import ID3v1ExtractorSpec._

  "An ID3v1Extractor" should "reject an empty frame" in {
    ID3v1Extractor.providerFor(TailBuffer(128)) shouldBe 'empty
  }

  it should "reject a frame which is too small" in {
    val data = new Array[Byte](127)
    val buffer = TailBuffer(128, ByteString(fillArray(data, "TAG")))

    ID3v1Extractor.providerFor(buffer) shouldBe 'empty
  }

  it should "reject a frame with no valid ID" in {
    val data = new Array[Byte](128)
    val buffer = TailBuffer(128, ByteString(fillArray(data, "TAJ")))

    ID3v1Extractor.providerFor(buffer) shouldBe 'empty
  }

  /**
    * Reads the content of the given test file and adds it to a tail buffer.
    * It can then be checked whether the correct data was extracted.
    *
    * @param fileName the name of the test file
    * @return the populated tail buffer
    */
  private def bufferForFile(fileName: String): TailBuffer = {
    val uri = getClass.getResource("/" + fileName)
    val path = Paths get uri.toURI
    val content = Files readAllBytes path
    TailBuffer(128, ByteString(content))
  }

  it should "extract data from a valid frame" in {
    val provider = ID3v1Extractor.providerFor(bufferForFile("test.mp3")).get

    provider.artist.get should be("Testinterpret")
    provider.title.get should be("Testtitle")
    provider.album.get should be("A Test Collection")
    provider.inceptionYear.get should be(2006)
    provider.trackNo shouldBe 'empty
  }

  it should "extract the track number if defined" in {
    val provider =
      ID3v1Extractor.providerFor(bufferForFile("testMP3id3v1.mp3")).get

    provider.title.get should be("Test Title")
    provider.trackNo.get should be(1)
  }

  /**
    * Creates a tail buffer and prepares it for a test about string extraction.
    * A valid frame is created with the given string written to the title
    * field. Then a provider is created.
    *
    * @param txt the text
    * @return the tag provider
    */
  private def providerFromStringExtractionTest(txt: String): MetaDataProvider = {
    val data = new Array[Byte](128)
    fillArray(data, "TAG")
    fillArray(data, txt, 3)
    ID3v1Extractor.providerFor(TailBuffer(128, ByteString(data))).get
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
