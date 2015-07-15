package de.oliver_heger.splaya

/**
 * The package object for the ''mp3'' package. It contains helper functions
 * related to audio stream processing.
 */
import java.io.InputStream
package object mp3 {
  /**
   * Extracts a single byte from the given buffer and converts it to an
   * (unsigned) integer.
   * @param buf the byte buffer
   * @param idx the index in the buffer
   * @return the resulting unsigned integer
   */
  def extractByte(buf: Array[Byte], idx: Int): Int =
    buf(idx).toInt & 0xFF

  /**
   * Skips the given number of bytes from the specified input stream. This
   * method calls the stream's ''skip()'' in a loop until the number of bytes
   * to skip is reached or the end of the stream is reached.
   * @param in the input stream
   * @param count the number of bytes to skip
   * @return the number of bytes actually skipped
   */
  def skipStream(in: InputStream, count: Long): Long = {
    var size = count
    var skipped = 0L
    var totalSkipped = 0L
    while (size > 0 && skipped >= 0) {
      skipped = in.skip(size)
      if (skipped != -1) {
        size -= skipped
        totalSkipped += skipped
      }
    }
    totalSkipped
  }
}
