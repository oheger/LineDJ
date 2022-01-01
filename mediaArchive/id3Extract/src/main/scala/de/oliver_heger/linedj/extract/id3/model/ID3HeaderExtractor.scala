/*
 * Copyright 2015-2022 The Developers Team.
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

import java.nio.charset.StandardCharsets

import akka.util.ByteString

/**
  * Companion object for ''ID3DataExtractor''.
  */
object ID3HeaderExtractor {
  /** Constant for the size of an ID3 header. */
  val ID3HeaderSize = 10

  /** The string identifying an ID3 header. */
  private val HeaderID = "ID3".getBytes(StandardCharsets.UTF_8)

  /** The index of the version number in the header. */
  private val IdxVersion = 3

  /** The index of the size information in the header. */
  private val IdxSize = 6

  /** The length of the header size in bytes. */
  private val SizeLength = 4

  /**
    * Tests whether the specified data contains an ID3 header. This method
    * checks whether the given chunk starts with the ID3 header tag.
    *
    * @param data the data chunk to be checked
    * @return '''true''' if this data contains an ID3 header, '''false'''
    *         otherwise
    */
  private def isID3Header(data: ByteString): Boolean =
    data.length >= ID3HeaderSize && data.startsWith(HeaderID)

  /**
    * Creates an ''ID3Header'' object from a binary representation of an ID3
    * header. The passed in data chunk is expected to contain a valid header.
    *
    * @param data the data with the header in its binary form
    * @return the extracted ''ID3Header'' object
    */
  private def createID3Header(data: ByteString): ID3Header =
    ID3Header(size = id3Size(data), version = extractByte(data, IdxVersion))

  /**
    * Calculates the size of the whole ID3 tag block (excluding the ID3 header).
    * This method returns the number of bytes which have to be skipped in order
    * to read over the ID3 data.
    *
    * @param header the array with the ID3 header
    * @return the size of the ID3 block (minus header size)
    */
  private def id3Size(header: ByteString): Int =
    extractSizeInt(header, IdxSize, SizeLength)
}

/**
  * A class for evaluating and extracting ID3-header information from audio
  * files.
  *
  * This class can be invoked with a chunk of data obtained from an audio file.
  * It checks whether this data chunk starts with an ID3 header. If so, the
  * header is extracted and made available under a special representation.
  *
  * Implementation note: Instances of this class can safely be shared between
  * multiple threads; they are state-less.
  */
class ID3HeaderExtractor {

  import ID3HeaderExtractor._

  /**
    * Tries to extract an [[ID3Header]] object from the given data array. If
    * this succeeds, the returned option is defined and contains meta
    * information about the extracted header. Otherwise, result is ''None''.
    *
    * @param data the chunk with ID3 header data
    * @return an option with the extracted header
    */
  def extractID3Header(data: ByteString): Option[ID3Header] =
    if (isID3Header(data)) Some(createID3Header(data))
    else None
}

/**
  * A class representing the header of an ID3v2 tag as used within MP3 audio
  * files.
  *
  * ID3 tags can contain meta information about audio files. Their binary
  * representation starts with a header block containing some description about
  * the tag. This class is a simple representation of such a header. It is not
  * very valuable on itself, but is required for enhanced processing of ID3
  * information, which is a frequent task when dealing with MP3 files.
  *
  * @param version the version of this ID3 header (e.g. 3 for ID3v2, version 3)
  * @param size    the size of the data stored in the associated tag (excluding
  *                header size)
  */
case class ID3Header(version: Int, size: Int)
