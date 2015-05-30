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

import java.io.ByteArrayOutputStream
import java.util

import org.scalatest.{FlatSpec, Matchers}

object Mp3DataExtractorSpec {
  /**
   * Writes a byte repeatedly in an output stream.
   * @param bos the output stream
   * @param byte the byte to be written
   * @param count the repeat count
   */
  private def writeBytes(bos: ByteArrayOutputStream, byte: Int, count: Int): Unit = {
    val data = new Array[Byte](count)
    util.Arrays.fill(data, byte.toByte)
    bos write data
  }

  /**
   * Convenience method for writing the bytes of a frame header in a single
   * operation. The first byte is always set to 0xFF.
   * @param bos the output stream
   * @param b2 byte 2
   * @param b3 byte 3
   * @param b4 byte 4
   */
  private def writeFrame(bos: ByteArrayOutputStream, b2: Int, b3: Int, b4: Int) {
    bos.write(0xFF)
    bos.write(b2)
    bos.write(b3)
    bos.write(b4)
  }
}

/**
 * Test class for ''Mp3DataExtractor''.
 */
class Mp3DataExtractorSpec extends FlatSpec with Matchers {

  import Mp3DataExtractorSpec._

  "A Mp3DataExtractor" should "have meaningful start values" in {
    val extractor = new Mp3DataExtractor
    extractor.getLayer should be(0)
    extractor.getVersion should be(-1)
    extractor.getSampleRate should be(0)
    extractor.getMaxBitRate should be(0)
    extractor.getMinBitRate should be(0)
    extractor.getDuration should be(0)
  }

  /**
   * Helper method for checking whether the default properties have been
   * correctly extracted from a data chunk representing an Mp3 header.
   * @param bos the stream containing the audio data
   * @return the test extractor
   */
  private def checkDefaultProperties(bos: ByteArrayOutputStream): Mp3DataExtractor = {
    val extractor = new Mp3DataExtractor
    extractor addData bos.toByteArray should be(extractor)

    checkDefaultProperties(extractor)
    extractor
  }

  /**
   * Helper method for checking whether the specified extractor has extracted
   * a frame with the expected properties.
   * @param extractor the extractor to be checked
   */
  private def checkDefaultProperties(extractor: Mp3DataExtractor): Unit = {
    extractor.getVersion should be(Mp3DataExtractor.MpegV2)
    extractor.getLayer should be(Mp3DataExtractor.Layer3)
    extractor.getMaxBitRate should be(80000)
    extractor.getMinBitRate should be(80000)
    extractor.getSampleRate should be(24000)
  }

  it should "find the start of the next frame" in {
    val bos = new ByteArrayOutputStream
    writeBytes(bos, 0xFF, 32)
    writeBytes(bos, 0, 16)
    writeBytes(bos, 0xFF, 8)
    bos.write(0xF3)
    bos.write(0x96)
    bos.write(0)

    checkDefaultProperties(bos)
  }

  it should "skip invalid headers" in {
    val bos = new ByteArrayOutputStream
    writeFrame(bos, 0xEB, 0x96, 0)
    writeFrame(bos, 0xF9, 0x96, 0)
    writeFrame(bos, 0xF3, 0, 0)
    writeFrame(bos, 0xF3, 0xF0, 0)
    writeFrame(bos, 0xF3, 0x7C, 0)
    writeFrame(bos, 0xF3, 0x96, 0)

    val extractor = checkDefaultProperties(bos)
    extractor.getFrameCount should be(1)
  }

  /**
   * Tests a search for another frame which is interrupted because the stream
   * ends.
   */
  it should "handle incomplete frames" in {
    val bos = new ByteArrayOutputStream
    bos.write(0xFF)
    bos.write(0xFF)
    bos.write(0xF3)
    bos.write(0x96)
    val extractor = new Mp3DataExtractor

    extractor addData bos.toByteArray
    extractor.getFrameCount should be(0)

    extractor addData new Array[Byte](1)
    extractor.getFrameCount should be(1)
    checkDefaultProperties(extractor)
  }

  /**
   * Reads the content of a file and passes it to an extractor. The extractor
   * is returned.
   * @param name the name of the file to be processed
   * @return the extractor
   */
  private def processFile(name: String): Mp3DataExtractor = {
    val extractor = new Mp3DataExtractor
    val in = getClass getResourceAsStream "/" + name
    try {
      val buf = new Array[Byte](37)
      var len = in read buf
      while (len > 0) {
        extractor addData buf.take(len)
        len = in read buf
      }
    } finally {
      in.close()
    }
    extractor
  }

  /**
   * Processes the specified file and checks whether the expected duration was
   * calculated.
   * @param fileName the file name
   * @param expected the expected duration
   * @return the extractor used for the test
   */
  private def checkDuration(fileName: String, expected: Int): Mp3DataExtractor = {
    val extractor = processFile(fileName)
    math.round(extractor.getDuration) should be(expected +- 100)
    extractor
  }

  it should "calculate the correct duration of a test file" in {
    checkDuration("test.mp3", 10842)
  }

  it should "calculate the correct duration of another test file" in {
    checkDuration("test2.mp3", 6734)
  }

  it should "handle a variable bit rate" in {
    val bos = new ByteArrayOutputStream
    writeFrame(bos, 0xF3, 0x96, 0)
    writeBytes(bos, 1, 481) // content of frame 1
    writeFrame(bos, 0xF3, 0xA6, 0)
    writeBytes(bos, 1, 577) // content of frame 2
    writeFrame(bos, 0xF3, 0x86, 0)
    writeBytes(bos, 1, 385) // content of frame 3
    val extractor = new Mp3DataExtractor

    extractor addData bos.toByteArray
    extractor.getFrameCount should be(3)
    extractor.getMinBitRate should be(64000)
    extractor.getMaxBitRate should be(96000)
  }
}
