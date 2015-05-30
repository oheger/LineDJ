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

import de.oliver_heger.splaya.io.DynamicInputStream

object Mp3DataExtractor {
  /** Constant for the MPEG version 1. */
  final val MpegV1 = 3

  /** Constant for the MPEG version 2. */
  final val MpegV2 = 2

  /** Constant for the MPEG version 2.5. */
  final val MpegV2_5 = 0

  /** Constant for audio layer 1. */
  final val Layer1 = 3

  /** Constant for audio layer 2. */
  final val Layer2 = 2

  /** Constant for audio layer 3. */
  final val Layer3 = 1

  /** The synchronization byte. */
  private val SyncByte = 0xFF.toByte

  /** The length of a header in bytes. */
  private val HeaderLength = 3

  /** Bit rate table for MPEG V1, layer 1. */
  private val BitRateMpeg1L1 = Array(0, 32000, 64000, 96000, 128000, 160000,
    192000, 224000, 256000, 288000, 320000, 352000, 384000, 416000, 448000)

  /** Bit rate table for MPEG V1, layer 2. */
  private val BitRateMpeg1L2 = Array(0, 32000, 48000, 56000, 64000, 80000,
    96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000, 384000)

  /** Bit rate table for MPEG V1, layer 3. */
  private val BitRateMpeg1L3 = Array(0, 32000, 40000, 48000, 56000, 64000,
    80000, 96000, 112000, 128000, 160000, 192000, 224000, 256000, 320000)

  /** Bit rate table for MPEG V2/V2.5, layer 1. */
  private val BitRateMpeg2L1 = Array(0, 32000, 48000, 56000, 64000, 80000,
    96000, 112000, 128000, 144000, 160000, 176000, 192000, 224000, 256000)

  /** Bit rate table for MPEG V2/V2.5, layer 2 and 3. */
  private val BitRateMpeg2L2 = Array(0, 8000, 16000, 24000, 32000, 40000,
    48000, 56000, 64000, 80000, 96000, 112000, 128000, 144000, 160000)

  /** Sample rate table for MPEG V1. */
  private val SampleRateMpeg1 = Array(44100, 48000, 32000)

  /** Sample rate table for MPEG V2. */
  private val SampleRateMpeg2 = Array(22050, 24000, 16000)

  /** Sample rate table for MPEG V2.5. */
  private val SampleRateMpeg2_5 = Array(11025, 12000, 8000)

  /** Sample rate table for all MPEG versions. */
  private val SampleRate = createSampleRateTable()

  /** Constant for the number of samples for a layer 1 frame. */
  private val SampleCountL1 = 384

  /** Constant for the number of samples for a layer 2 or 3 frame. */
  private val SampleCountL2 = 1152

  /**
   * Calculates the bit rate based on the given parameters.
   * @param mpegVer the MPEG version
   * @param layer the layer
   * @param code the code for the bit rate
   * @return the bit rate in bit per second
   */
  private def calculateBitRate(mpegVer: Int, layer: Int, code: Int): Int = {
    val arr =
      if (mpegVer == MpegV1) {
        layer match {
          case Layer1 => BitRateMpeg1L1
          case Layer2 => BitRateMpeg1L2
          case Layer3 => BitRateMpeg1L3
        }
      } else {
        if (layer == Layer1) BitRateMpeg2L1
        else BitRateMpeg2L2
      }
    arr(code)
  }

  /**
   * Calculates the sample rate based on the given parameters.
   * @param mpegVer the MPEG version
   * @param code the code for the sample rate
   * @return the sample rate in samples per second
   */
  private def calculateSampleRate(mpegVer: Int, code: Int): Int =
    SampleRate(mpegVer)(code)

  /**
   * Calculates the length of an MPEG frame based on the given parameters.
   * @param layer the layer
   * @param bitRate the bit rate
   * @param sampleRate the sample rate
   * @param padding the padding flag
   * @return the length of the frame in bytes
   */
  private def calculateFrameLength(layer: Int, bitRate: Int, sampleRate: Int,
                                   padding: Int): Int = {
    if (layer == Layer1)
      (12 * bitRate / sampleRate + padding) * 4
    else
      144 * bitRate / sampleRate + padding
  }

  /**
   * Calculates the duration of a MPEG frame based on the given parameters.
   * @param layer the layer
   * @param sampleRate the sample rate
   * @return the duration of this frame in milliseconds
   */
  private def calculateDuration(layer: Int, sampleRate: Int): Float = {
    val sampleCount = if (layer == Layer1) SampleCountL1
    else SampleCountL2
    (1000.0f / sampleRate) * sampleCount
  }

  /**
   * Creates the complete array for the sample rate mapping.
   * @return the table for the sample rates
   */
  private def createSampleRateTable(): Array[Array[Int]] = {
    val arr = new Array[Array[Int]](4)
    arr(MpegV1) = SampleRateMpeg1
    arr(MpegV2) = SampleRateMpeg2
    arr(MpegV2_5) = SampleRateMpeg2_5
    arr
  }

  /**
   * A class representing the bit field of an MPEG header. It allows convenient
   * access to specific bit groups.
   *
   * @param data the array with the bytes of the header
   */
  private class HeaderBitField(data: Array[Byte]) {
    /** The internal value. */
    private val value = calculateValue()

    /**
     * Returns the value of the bit group from the given start and end index.
     * E.g. ''from'' = 0, ''to'' = 3 will return the value of the first 4 bits.
     * @param from the from index
     * @param to the to index
     * @return the value of this group of bits
     */
    def apply(from: Int, to: Int): Int = {
      val shiftVal = value >> from
      val mask = (1 << (to - from + 1)) - 1
      shiftVal & mask
    }

    /**
     * Returns the value of the bit with the given index. The bit index is
     * 0-based. Result is either 0 or 1, depending on the value of this bit.
     * @param bit the bit index
     * @return the value of this bit
     */
    def apply(bit: Int): Int = apply(bit, bit)

    /**
     * Calculates the value of this field as an integer.
     * @return the integer value of this header field
     */
    private def calculateValue(): Int =
      data.foldLeft(0)(appendByte)

    private def appendByte(current: Int, b: Byte): Int = {
      val next = (current << 8) | toUnsignedInt(b)
      next
    }
  }

  /**
   * An internally data class containing meta data about an MPEG frame.
   *
   * @param length the length of the associated frame in bytes
   * @param mpegVersion a code for the MPEG version
   * @param layer the audio layer version
   * @param bitRate the bit rate of the associated frame (in bps)
   * @param sampleRate the sample rate (in samples per second)
   * @param duration the duration of this frame (in milliseconds)
   */
  private case class MpegHeader(length: Int, mpegVersion: Int, layer: Int, bitRate: Int,
                                sampleRate: Int, duration: Float)

}

/**
 * A class for extracting meta data from mp3 audio files.
 *
 * An instance of this class can be used for processing a single MP3 audio
 * file. With the ''addData()'' method chunks of the file of arbitrary length
 * can be added. Each chunk is processed immediately: all MP3 frames are
 * processed, and corresponding data is extracted.
 *
 * When all chunks of the file have been added (or at any time if intermediate
 * results are of interest) the various get methods can be called for obtaining
 * the accumulated meta data obtained during processing.
 *
 * Implementation note: This class is not thread-safe. Adding of data chunks
 * and querying meta data can be performed in a single thread only.
 */
class Mp3DataExtractor {

  import Mp3DataExtractor._

  /** The dynamic stream used internally for data processing. */
  private val buffer = new DynamicInputStream

  /** The MP3 version. */
  private var version = -1

  /** The layer version. */
  private var layer = 0

  /** The sample rate. */
  private var sampleRate = 0

  /** The maximum bit rate. */
  private var maxBitRate = 0

  /** The minimum bit rate. */
  private var minBitRate = 0

  /** The duration. */
  private var duration = 0f

  /** The number of frames which have been processed. */
  private var frameCount = 0

  /** The number of bytes that need to be skipped. */
  private var bytesToSkip = 0

  /** Flag whether the sync byte has been found. */
  private var syncFound = false

  /**
   * Returns the MPEG version of the processed file.
   * @return the version
   */
  def getVersion: Int = version

  /**
   * Returns the audio layer version of the processed file.
   * @return the audio layer version
   */
  def getLayer: Int = layer

  /**
   * Returns the maximum bit rate found in the processed file (in bps). For
   * files with variable bit rate encoding there will be different minimum and
   * maximum bit rates.
   * @return the maximum bit rate of the processed file
   */
  def getMaxBitRate: Int = maxBitRate

  /**
   * Returns the minimum bit rate found in the processed file (in bps) For
   * files with variable bit rate encoding there will be different minimum and
   * maximum bit rates.
   * @return the minimum bit rate of the processed file
   */
  def getMinBitRate: Int = minBitRate

  /**
   * Returns the sample rate of the processed file (in samples per second).
   * @return the sample rate
   */
  def getSampleRate: Int = sampleRate

  /**
   * Returns the duration of the processed file (in milliseconds)
   * @return the duration
   */
  def getDuration: Float = duration

  /**
   * Returns the number of frames that have been processed by this object so
   * far.
   * @return the number of MP3 frames
   */
  def getFrameCount: Int = frameCount

  /**
   * Adds another chunk of data to this object and processes it. The meta data
   * about the file to be processed is directly updated.
   * @param data the data to be processed
   * @return a reference to this object
   */
  def addData(data: Array[Byte]): Mp3DataExtractor = {
    buffer append data
    if (handleSkip()) {
      process()
    }
    this
  }

  /**
   * Performs a skip operation if necessary. The return value indicates whether
   * all bytes could be skipped, and processing can continue. The exhausted
   * flag is updated accordingly.
   * @return a flag whether all bytes could be skipped
   */
  private def handleSkip(): Boolean = {
    if (bytesToSkip > 0) {
      bytesToSkip -= buffer.skip(bytesToSkip).toInt
    }
    bytesToSkip == 0
  }

  /**
   * Processes the data currently available.
   */
  private def process(): Unit = {
    var exhausted = false
    do {
      if (syncFound || sync()) {
        syncFound = true

        readHeader() match {
          case Some(header) =>
            syncFound = false
            val optProcessed = createHeader(new HeaderBitField(header)) map processFrameHeader
            exhausted = !(optProcessed getOrElse true)

          case None =>
            exhausted = true
        }

      } else exhausted = true
    } while (!exhausted)
  }

  /**
   * Tries to find the synchronization byte in the stream.
   * @return a flag whether the byte could be found
   */
  private def sync(): Boolean = buffer find SyncByte

  /**
   * Tries to read the bytes for a frame header. If this is not possible -
   * because no more bytes are available -, result is ''None''. Note that this
   * method does not proceed the buffer stream; if it turns out that the
   * header is invalid, the ''sync()'' operation has to start at the very same
   * position.
   * @return an option with the bytes of the header
   */
  private def readHeader(): Option[Array[Byte]] = {
    if (buffer.available() >= HeaderLength) {
      buffer mark HeaderLength
      val header = new Array[Byte](HeaderLength)
      buffer read header
      buffer.reset()
      Some(header)
    } else None
  }

  /**
   * Creates an ''MpegHeader'' object based on the given header field. If the
   * header field contains invalid values, result is ''None'',
   * @param bits the header bit field
   * @return the ''MpegHeader''
   */
  private def createHeader(bits: HeaderBitField): Option[MpegHeader] = {
    if (bits(21, 23) != 7) None
    else {
      val mpegVer = bits(19, 20)
      val layer = bits(17, 18)
      val bitRateCode = bits(12, 15)
      val sampleRateCode = bits(10, 11)
      val padding = bits(9)

      if (mpegVer == 1 || layer == 0 || bitRateCode == 0 || bitRateCode == 15 ||
        sampleRateCode == 3) None
      else {
        val bitRate = calculateBitRate(mpegVer, layer, bitRateCode)
        val sampleRate = calculateSampleRate(mpegVer, sampleRateCode)
        val length = calculateFrameLength(layer, bitRate,
          sampleRate, padding)
        val duration = calculateDuration(layer, sampleRate)
        Some(MpegHeader(bitRate = bitRate, sampleRate = sampleRate, layer = layer,
          mpegVersion = mpegVer, length = length, duration = duration))
      }
    }
  }

  /**
   * Processes a header that could be successfully decoded. This method updates
   * the accumulated statistics maintained by this object. It also triggers
   * skipping of the frame data.
   * @param header the data object representing the header
   * @return a flag whether the frame data could be fully skipped
   */
  private def processFrameHeader(header: MpegHeader): Boolean = {
    syncFound = false
    frameCount += 1
    sampleRate = header.sampleRate
    maxBitRate = math.max(maxBitRate, header.bitRate)
    minBitRate = if (minBitRate == 0) header.bitRate
    else math.min(minBitRate, header.bitRate)
    version = header.mpegVersion
    layer = header.layer
    duration += header.duration

    bytesToSkip = header.length - 1 // 1 byte of header already read
    handleSkip()
  }
}
