/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.archive

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
}
