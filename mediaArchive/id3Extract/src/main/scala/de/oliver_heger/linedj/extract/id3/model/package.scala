/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.extract.id3

import akka.util.ByteString

import scala.annotation.tailrec

/**
  * The package object for the ''model'' package.
  *
  * This object contains some utility functions which are used by multiple
  * classes.
  */
package object model {
  /** Factor for shifting a byte position in an integer representing size. */
  val SizeByteShift = 7

  /**
    * Converts a byte to an unsigned integer.
    *
    * @param b the byte
    * @return the converted integer
    */
  def toUnsignedInt(b: Byte): Int = b.toInt & 0xFF

  /**
    * Extracts a single byte from the given buffer and converts it to an
    * (unsigned) integer.
    *
    * @param buf the byte buffer
    * @param idx the index in the buffer
    * @return the resulting unsigned integer
    */
  def extractByte(buf: ByteString, idx: Int): Int = toUnsignedInt(buf(idx))

  /**
    * Extracts an integer of the given size representing a tag or frame size
    * from the given buffer. The number of bits used in the single bytes
    * depends on the format and the frame the size is for; therefore, the
    * shift factor is passed to this function.
    *
    * @param buf    a buffer with the data to be processed
    * @param ofs    the offset into the buffer
    * @param length the length of the size integer (in bytes)
    * @param shift  the factor for shifting bytes
    * @return the extracted size value
    */
  def extractSizeInt(buf: ByteString, ofs: Int, length: Int, shift: Int = SizeByteShift): Int = {
    @tailrec def extractSizeByte(value: Int, pos: Int): Int =
      if (pos >= length) value
      else extractSizeByte((value << shift) +
        extractByte(buf, ofs + pos), pos + 1)

    extractSizeByte(0, 0)
  }
}
