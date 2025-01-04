/*
 * Copyright 2015-2025 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''ID3DataExtractor''.
  */
class ID3HeaderExtractorSpec extends AnyFlatSpec with Matchers:
  "An ID3HeaderExtractor" should "define the correct ID3 header size" in:
    ID3HeaderExtractor.ID3HeaderSize should be(10)

  it should "reject a buffer which is too small" in:
    val extractor = new ID3HeaderExtractor
    val data = ByteString(new Array[Byte](ID3HeaderExtractor.ID3HeaderSize - 1))

    extractor extractID3Header data shouldBe empty

  it should "reject a buffer that does not contain header information" in:
    val extractor = new ID3HeaderExtractor
    val data = ByteString(FileTestHelper.toBytes("ID201234569870"))

    extractor extractID3Header data shouldBe empty

  /**
    * Creates a array for the binary data of an ID3 header. The array is
    * already filled with the expected header bytes.
    *
    * @return the prepared data array
    */
  private def prepareID3HeaderArray(): Array[Byte] =
    def b(n: Int) = n.toByte

    val fill = b(0)
    Array(b(0x49), b(0x44), b(0x33), fill, fill, fill, fill, fill, fill, fill)

  it should "create a correct ID3Header object for valid data" in:
    val data = prepareID3HeaderArray()
    data(3) = 2
    data(9) = 42
    val extractor = new ID3HeaderExtractor

    val header = extractor.extractID3Header(ByteString(data)).get
    header.size should be(42)
    header.version should be(2)

  it should "calculate the correct frame size from all components" in:
    val header = prepareID3HeaderArray()
    header(6) = 0x7F
    header(7) = 0x7F
    header(8) = 0x7F
    header(9) = 0x7F
    val extractor = new ID3HeaderExtractor

    val headerObj = extractor.extractID3Header(ByteString(header)).get
    headerObj.size should be(0xFFFFFFF)
