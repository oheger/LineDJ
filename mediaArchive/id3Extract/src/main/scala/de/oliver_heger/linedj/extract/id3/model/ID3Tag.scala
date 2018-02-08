/*
 * Copyright 2015-2018 The Developers Team.
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

import akka.util.ByteString

object ID3Tag {
  /** Text encoding ISO-88559-1. */
  private val EncISO88591 = TextEncoding("ISO-8859-1", doubleByte = false)

  /** Text encoding UTF 16. */
  private val EncUTF16 = TextEncoding("UTF-16", doubleByte = true)

  /** Text encoding UTF-16 without BOM. */
  private val EncUTF16BE = TextEncoding("UTF-16BE", doubleByte = true)

  /** Text encoding UTF-8. */
  private val EncUTF8 = TextEncoding("UTF-8", doubleByte = false)

  /**
    * An array with all supported text encodings in an order which corresponds
    * to the text encoding byte used within ID3v2 tags.
    */
  private val Encodings = Array(EncISO88591, EncUTF16, EncUTF16BE, EncUTF8)

  /** Constant for an empty string. */
  private val Blank = ""

  /**
    * Extracts the name of a tag from binary frame data.
    *
    * @param data   the data
    * @param ofs    the offset where the tag name starts
    * @param length the length of the tag name
    * @return the extracted tag name
    */
  def extractName(data: ByteString, ofs: Int, length: Int): String =
    extractString(data, ofs, length, EncISO88591.encName)

  /**
    * Extracts a string from the given byte string using the specified
    * encoding.
    *
    * @param buf the byte string
    * @param ofs the start offset of the string in the buffer
    * @param len the length of the string
    * @param enc the name of the encoding
    * @return the resulting string
    */
  private def extractString(buf: ByteString, ofs: Int, len: Int,
                            enc: String): String =
    if (len <= 0) Blank
    else buf.slice(ofs, ofs + len).decodeString(enc)
}

/**
  * A data class describing a single tag in an ID3v2 frame. These tags contain
  * the actual data. There are methods for querying data either in binary form or
  * as strings.
  *
  * @param name the name of this tag (this is the internal name as defined by
  *             the ID3v2 specification)
  * @param data an object with the content of this tag
  */
case class ID3Tag(name: String, data: ByteString) {

  import ID3Tag._

  /**
    * Returns the content of this tag as a byte array. The return value is a copy
    * of the internal data of this tag.
    *
    * @return the content of this tag as a byte array
    */
  def asBytes: Array[Byte] =
    data.toArray

  /**
    * Returns the content of this tag as a string. If the tag contains an
    * encoding specification, it is taken into account.
    *
    * @return the content of this tag as string
    */
  def asString: String = {
    if (data.isEmpty) Blank
    else {
      var ofs = 0
      val encFlag = extractByte(data, 0)
      val encoding = if (encFlag < Encodings.length) {
        ofs = 1
        Encodings(encFlag)
      } else Encodings(0)
      val len = calcStringLength(encoding.doubleByte) - ofs
      extractString(data, ofs, len, encoding.encName)
    }
  }

  /**
    * Determines the length of this tag's string content by skipping 0 bytes at
    * the end of the buffer.
    *
    * @param doubleByte a flag whether a double byte encoding is used
    */
  private def calcStringLength(doubleByte: Boolean): Int = {
    var length = data.length
    if (doubleByte) {
      while (length >= 2 && data(length - 1) == 0 && data(length - 2) == 0) {
        length -= 2
      }
    } else {
      while (length >= 1 && data(length - 1) == 0) {
        length -= 1
      }
    }
    length
  }
}

/**
  * A simple data class representing a text encoding.
  *
  * @param encName    the name of the text encoding
  * @param doubleByte a flag whether this encoding uses double bytes
  */
private case class TextEncoding(encName: String, doubleByte: Boolean)
