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

package de.oliver_heger.linedj.shared.archive.metadata

import de.oliver_heger.linedj.shared.archive.metadata.Checksums.ChecksumEncoding.Lower

import scala.annotation.tailrec

/**
  * An object defining classes for and functionality related to different
  * checksums used for metadata of audio files.
  *
  * The media archive uses checksums partly to identify specific elements. This
  * is possible if they are created based on cryptographic hash functions.
  */
object Checksums:
  object MediumChecksum:
    /** Constant representing an undefined medium checksum. */
    final val Undefined = MediumChecksum("")

  /**
    * A value class representing a checksum calculated based on the content of
    * a medium.
    *
    * @param checksum the actual checksum value
    */
  case class MediumChecksum(checksum: String) extends AnyVal

  object MediaFileChecksum:
    /** Constant representing an undefined media file checksum. */
    final val Undefined = MediaFileChecksum("")

  /**
    * A value class representing a checksum calculated based on the content of
    * a media file.
    *
    * @param checksum the actual checksum value
    */
  case class MediaFileChecksum(checksum: String) extends AnyVal

  /**
    * An enumeration class defining the encoding when computing a checksum.
    *
    * Checksums are typically hash values that have to be converted to hex
    * strings. The exact encoding for this conversion (if lowercase or
    * uppercase letters are to be used) is determined by the constants defined
    * here.
    *
    * @param alphabet the alphabet for the encoding
    */
  enum ChecksumEncoding(val alphabet: String):
    /**
      * Constant to determine that lowercase letters should be used to
      * represent a checksum.
      */
    case Lower extends ChecksumEncoding("0123456789abcdef")

    /**
      * Constant to determine that uppercase letters should be used to
      * represent a checksum.
      */
    case Upper extends ChecksumEncoding("0123456789ABCDEF")

  /**
    * Converts the given byte array into a hex string representation.
    * Optionally, the encoding to be used for this purpose can be provided.
    *
    * @param bytes the byte array
    * @return the resulting hex string
    */
  def toHexString(bytes: Array[Byte], encoding: ChecksumEncoding = Lower): String =
    def toHexChar(value: Int): Char = encoding.alphabet(value & 0x0F)

    val buf = new java.lang.StringBuilder(bytes.length * 2)

    @tailrec def convertBytes(index: Int): Unit =
      if index < bytes.length then
        buf.append(toHexChar(bytes(index) >>> 4)).append(toHexChar(bytes(index)))
        convertBytes(index + 1)

    convertBytes(0)
    buf.toString
