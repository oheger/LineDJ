/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.util.ByteString
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.{Files, Paths}

object ID3v1ExtractorSpec:
  /**
    * Writes the given text byte-wise into the specified array. This method can
    * be used to populate an array which can then be processed by the extractor.
    *
    * @param buf the array to be filled
    * @param txt the text to write
    * @param pos the optional start position
    * @return the array
    */
  private def fillArray(buf: Array[Byte], txt: String, pos: Int = 0): Array[Byte] =
    val txtBytes = FileTestHelper toBytes txt
    System.arraycopy(txtBytes, 0, buf, pos, txtBytes.length)
    buf

/**
  * Test class for ''ID3v1Extractor''.
  */
class ID3v1ExtractorSpec extends AnyFlatSpec with Matchers with OptionValues with MockitoSugar:

  import ID3v1ExtractorSpec.*

  "An ID3v1Extractor" should "reject an empty frame" in:
    ID3v1Extractor.providerFor(ByteString.empty) shouldBe empty

  it should "reject a frame which is too small" in:
    val data = new Array[Byte](127)
    val frame = ByteString(fillArray(data, "TAG"))

    ID3v1Extractor.providerFor(frame) shouldBe empty

  it should "reject a frame with no valid ID" in:
    val data = new Array[Byte](128)
    val frame = ByteString(fillArray(data, "TAJ"))

    ID3v1Extractor.providerFor(frame) shouldBe empty

  /**
    * Reads the content of the given test file and returns the last bytes in
    * the size of an ID3v1 frame. This can then be used as input for the
    * extractor.
    *
    * @param fileName the name of the test file
    * @return the ID3v1 frame from the end of the file
    */
  private def extractID3FrameFromFile(fileName: String): ByteString =
    val uri = getClass.getResource("/" + fileName)
    val path = Paths get uri.toURI
    val content = Files readAllBytes path
    ByteString(content.takeRight(128))

  it should "extract data from a valid frame" in:
    val provider = ID3v1Extractor.providerFor(extractID3FrameFromFile("test.mp3")).get

    provider.artist.get should be("Testinterpret")
    provider.title.get should be("Testtitle")
    provider.album.get should be("A Test Collection")
    provider.inceptionYear.get should be(2006)
    provider.trackNo shouldBe empty

  it should "extract the track number if defined" in:
    val provider =
      ID3v1Extractor.providerFor(extractID3FrameFromFile("testMP3id3v1.mp3")).get

    provider.title.get should be("Test Title")
    provider.trackNo.get should be(1)

  /**
    * Creates a synthetic frame for a test about string extraction. The frame
    * contains the given string in the title field. Then a provider is created
    * by calling the extractor with this input.
    *
    * @param txt the text
    * @return the tag provider
    */
  private def providerFromStringExtractionTest(txt: String): MetadataProvider =
    val data = new Array[Byte](128)
    fillArray(data, "TAG")
    fillArray(data, txt, 3)
    ID3v1Extractor.providerFor(ByteString(data)).value

  it should "ignore a string consisting only of 0 bytes" in:
    val provider = providerFromStringExtractionTest("")

    provider.title shouldBe empty

  it should "ignore a string consisting only of whitespace" in:
    val provider = providerFromStringExtractionTest(" " * 32)

    provider.title shouldBe empty

  it should "trim text data" in:
    val provider = providerFromStringExtractionTest(" Leading +   trailing Space!!   ")

    provider.title.get should be("Leading +   trailing Space!!")
