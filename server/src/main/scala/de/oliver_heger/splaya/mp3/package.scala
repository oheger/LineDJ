package de.oliver_heger.splaya

/**
 * The package object for the ''mp3'' package.
 *
 * This object contains some utility functions which are used by multiple
 * classes.
 */
package object mp3 {
  /**
   * Converts a byte to an unsigned integer.
   * @param b the byte
   * @return the converted integer
   */
  def toUnsignedInt(b: Byte): Int = b.toInt & 0xFF

  /**
   * Extracts a single byte from the given buffer and converts it to an
   * (unsigned) integer.
   * @param buf the byte buffer
   * @param idx the index in the buffer
   * @return the resulting unsigned integer
   */
  def extractByte(buf: Array[Byte], idx: Int): Int = toUnsignedInt(buf(idx))

  /**
   * Extracts a string from the given byte array using the specified encoding.
   * @param buf the byte array
   * @param ofs the start offset of the string in the buffer
   * @param len the length of the string
   * @param enc the name of the encoding
   * @return the resulting string
   */
  private[mp3] def extractString(buf: Array[Byte], ofs: Int, len: Int,
                                 enc: String): String =
    new String(buf, ofs, len, enc)
}
