package de.oliver_heger.splaya.mp3

import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * A specialized stream class which can be used to extract single frames of
 * MPEG audio files.
 *
 * Instances of this class are constructed with an underlying stream which
 * should point to an audio file. Read operations are possible in the usual
 * way. However, there are special methods for searching and extracting
 * headers of MPEG frames. Some meta information of frames can be queried.
 *
 * @param in the underlying input stream
 */
class MpegStream(in: InputStream)
  extends PushbackInputStream(in, MpegStream.HeaderSize) {
  /** The current MPEG header. */
  private var currentHeader: Option[MpegHeader] = None

  /** A flag whether the end of the stream is reached. */
  private var endOfStream = false

  /**
   * Searches for the next MPEG frame header from the current stream position
   * on. This method advances the underlying input stream until it finds a
   * valid frame header or the end of the stream is reached. In the former case
   * a corresponding ''MpegHeader'' object is created. In the latter case there
   * are no more headers, so the end of the stream is probably reached.
   * @return an option with the header of the next MPEG frame
   * @throws IOException if an IO error occurs
   */
  @throws(classOf[IOException])
  def nextFrame(): Option[MpegHeader] = {
    var frame: Option[MpegHeader] = None
    while (!endOfStream && frame.isEmpty) {
      findFrameSyncByte()
      if (!endOfStream) {
        val headerField = createHeaderField()
        if (!endOfStream) {
          frame = createHeader(headerField)
          if (frame.isEmpty) {
            pushBack(headerField)
          }
        }
      }
    }

    currentHeader = frame
    frame
  }

  /**
   * Skips the current MPEG frame. This method can be called after a valid
   * MPEG header has been retrieved using ''nextFrame()''. In this case the
   * underlying stream is advanced to the end of the associated MPEG frame.
   * Otherwise, this method has no effect. The return value indicates whether
   * a frame could be skipped.
   * @return '''true''' if a frame could be skipped, '''false''' otherwise
   * @throws IOException if an IO error occurs
   */
  @throws(classOf[IOException])
  def skipFrame(): Boolean = {
    if (currentHeader.isDefined) {
      skipStream(in, currentHeader.get.length - MpegStream.HeaderSize)
      currentHeader = None
      true
    } else {
      false
    }
  }

  /**
   * Advances the underlying stream until the first byte of frame sync is found.
   */
  private def findFrameSyncByte() {
    var found = false
    while (!found && !endOfStream) {
      if (nextByte() == 0xFF) {
        found = true
      }
    }
  }

  /**
   * Creates a bit field for the MPEG frame header.
   * @return the bit field
   */
  private def createHeaderField(): HeaderBitField = {
    val field = new HeaderBitField
    field <<= nextByte()
    field <<= nextByte()
    field <<= nextByte()
    field
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
        val bitRate = MpegStream.calculateBitRate(mpegVer, layer, bitRateCode)
        val sampleRate = MpegStream.calculateSampleRate(mpegVer, sampleRateCode)
        val length = MpegStream.calculateFrameLength(layer, bitRate,
          sampleRate, padding)
        val duration = MpegStream.calculateDuration(layer, sampleRate)
        Some(MpegHeader(bitRate = bitRate, sampleRate = sampleRate, layer = layer,
          mpegVersion = mpegVer, length = length, duration = duration))
      }
    }
  }

  /**
   * Reads the next byte. This implementation reads a byte from the underlying
   * stream. Checks for the end of stream are done; if the end is reached, 0
   * is returned. (This is done for having a defined byte value when
   * constructing an MPEG header.)
   * @return the next byte
   */
  private def nextByte(): Int = {
    if (endOfStream) 0
    else {
      val result = read()
      if (result == -1) {
        endOfStream = true
        0
      } else result
    }
  }

  /**
   * Pushes the given header field back in the stream so that the bytes are
   * read again. This method is called if an invalid header was detected. Then
   * search has to continue at the next byte after the frame sync byte.
   * @param field the header bit field with the invalid frame header
   */
  private def pushBack(field: HeaderBitField) {
    unread(field.toArray)
  }

  /**
   * A class representing the bit field of an MPEG header. It allows convenient
   * access to specific bit groups.
   */
  private class HeaderBitField {
    /** The internal value. */
    private var value: Int = 0

    /**
     * Adds a byte to this field.
     * @param b the byte to be added
     */
    def <<=(b: Int) {
      value <<= 8
      value |= b
    }

    /**
     * Returns the value of the bit group from the given start and end index.
     * E.g. ''from'' = 0, ''to'' = 3 will return the value of the first 4 bits.
     * @param the from index
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
     * Returns the internal value of this field as an array. The array contains
     * 3 bytes.
     * @return the internal value of this field as byte array
     */
    def toArray: Array[Byte] = {
      val result = new Array[Byte](3)
      result(0) = apply(16, 23).toByte
      result(1) = apply(8, 15).toByte
      result(2) = apply(0, 7).toByte
      result
    }
  }
}

/**
 * The companion object of ''MpegStream''.
 */
object MpegStream {
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

  /** Constant for the size of an MPEG frame header in bytes. */
  private val HeaderSize = 4

  /**
   * Calculates the bit rate based on the given parameters.
   * @param mpegVer the MPEG version
   * @param layer the layer
   * @param code the code for the bit rate
   * @return the bit rate in bit per second
   */
  private def calculateBitRate(mpegVer: Int, layer: Int, code: Int): Int = {
    val arr =
      if (mpegVer == MpegHeader.MpegV1) {
        layer match {
          case MpegHeader.Layer1 => BitRateMpeg1L1
          case MpegHeader.Layer2 => BitRateMpeg1L2
          case MpegHeader.Layer3 => BitRateMpeg1L3
        }
      } else {
        if (layer == MpegHeader.Layer1) BitRateMpeg2L1
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
    if (layer == MpegHeader.Layer1)
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
    val sampleCount = if (layer == MpegHeader.Layer1) SampleCountL1
    else SampleCountL2
    (1000.0f / sampleRate) * sampleCount
  }

  /**
   * Creates the complete array for the sample rate mapping.
   * @return the table for the sample rates
   */
  private def createSampleRateTable(): Array[Array[Int]] = {
    val arr = new Array[Array[Int]](4)
    arr(MpegHeader.MpegV1) = SampleRateMpeg1
    arr(MpegHeader.MpegV2) = SampleRateMpeg2
    arr(MpegHeader.MpegV2_5) = SampleRateMpeg2_5
    arr
  }
}

/**
 * A data class containing meta data about an MPEG frame.
 *
 * This class describes a header of a frame in an MPEG audio file. Using the
 * [[de.oliver_heger.splaya.engine.io.MpegStream]] class, it is possible to
 * iterate over all audio frames querying the headers.
 *
 * @param length the length of the associated frame in bytes
 * @param mpegVersion a code for the MPEG version
 * @param layer the audio layer version
 * @param the bit rate of the associated frame (in bps)
 * @param sampleRate the sample rate (in samples per second)
 * @param duration the duration of this frame (in milliseconds)
 */
case class MpegHeader(length: Int, mpegVersion: Int, layer: Int, bitRate: Int,
  sampleRate: Int, duration: Float)

/**
 * The companion object of ''MpegHeader''.
 */
object MpegHeader {
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
}
