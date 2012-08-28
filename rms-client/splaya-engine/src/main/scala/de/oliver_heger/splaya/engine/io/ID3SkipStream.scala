package de.oliver_heger.splaya.engine.io

import java.io.InputStream
import java.io.PushbackInputStream

/**
 * A specialized input stream implementation which can be used to skip ID3 tags
 * in MP3 files.
 *
 * Obviously, the MP3 library used for playing audio files has some problems
 * with MP3 files containing certain images in their ID3 tags. Therefore, this
 * stream can be wrapped around a data stream. It checks whether the wrapped
 * stream contains ID3 tags. If this is the case, the whole ID3 tags are
 * skipped. For clients reading data from this stream it looks as if the data
 * file would not contain any ID3 information.
 *
 * After creating an instance passing in the wrapped stream, the ''skipID3()''
 * method has to be called. Afterwards the stream can be used in the usual way.
 * If ID3 information were present, it is skipped now.
 *
 * @param in the input stream to be filtered
 */
class ID3SkipStream(in: InputStream) extends PushbackInputStream(in,
  ID3SkipStream.HeaderSize) {
  /**
   * Skips all ID3 headers in the wrapped input at the current position. This
   * method should be called directly after creation of this object. It checks
   * whether the wrapped stream starts with one or more ID3 headers. If this is
   * the case, ID3 information is skipped, and result is the number of skipped
   * ID3 headers. Otherwise, this method has no effect, and result is 0.
   * @return the number of ID3 headers which have been skipped
   * @throws IOException if a read error occurs
   */
  def skipID3(): Int = {
    var count = 0
    while (skipNextID3()) {
      count += 1
    }
    count
  }

  /**
   * Skips the next ID3 header at the current position of the wrapped input
   * stream if present. The return value indicates whether an ID3 header was
   * found at the current position. If not, this method has no effect.
   * @return a flag whether an ID3 header was skipped
   * @throws IOException if a read error occurs
   */
  def skipNextID3(): Boolean = {
    val header = new Array[Byte](ID3SkipStream.HeaderSize)
    val read = in.read(header)
    if (read == ID3SkipStream.HeaderSize && ID3SkipStream.isID3Header(header)) {
      in.skip(id3Size(header))
      true
    } else {
      unread(header, 0, read)
      false
    }
  }

  /**
   * Calculates the size of the whole ID3 tag block (excluding the ID3 header).
   * This method returns the number of bytes which have to be skipped in order
   * to read over the ID3 data.
   * @param header the array with the ID3 header
   * @return the size of the ID3 block (minus header size)
   */
  protected[io] def id3Size(header: Array[Byte]): Int = {
    import ID3SkipStream._
    val f1 = header(IdxSize).toInt << F1
    val f2 = header(IdxSize + 1).toInt << F2
    val f3 = header(IdxSize + 2).toInt << F3
    f1 + f2 + f3 + header(IdxSize + 3).toInt
  }
}

/**
 * The companion object of ''ID3SkipStream''.
 */
object ID3SkipStream {
  /** The factor for byte 1 of the header size. */
  private val F1 = 21

  /** The factor for byte 2 of the header size. */
  private val F2 = 14

  /** The factor for byte 3 of the header size. */
  private val F3 = 7

  /** The index of the size information in the header. */
  private val IdxSize = 6

  /** The size of an ID3 header. */
  private val HeaderSize = 10

  /** The string identifying an ID3 header. */
  private val HeaderID = "ID3".getBytes

  /**
   * Tests whether the specified array contains an ID3 header. This method
   * checks whether the given array starts with the ID3 header tag.
   * @param arr the array to be checked
   * @return '''true''' if this array contains an ID3 header, '''false'''
   * otherwise
   */
  def isID3Header(arr: Array[Byte]): Boolean = {
    if (arr.length >= HeaderID.length) {
      var idx = 0
      var ok = true
      while (ok && idx < HeaderID.length) {
        ok = arr(idx) == HeaderID(idx)
        idx += 1
      }
      ok
    } else {
      false
    }
  }
}
