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

package de.oliver_heger.linedj.extract.id3.model

import akka.util.ByteString
import de.oliver_heger.linedj.extract.metadata.MetaDataProvider

/**
  * A class for extracting ID3 version 1 information from an audio file.
  *
  * ID3v1 data is stored as the last 128 bytes of an MP3 file (if it is
  * available at all). This class provides a method for checking such a
  * block of data and extracting ID3 information if available. The method
  * assumes that the ID3v1 frame has already been obtained using a
  * [[TailBuffer]] object.
  */
object ID3v1Extractor {
  /** Constant for the size of a binary buffer containing a valid ID3v1 frame. */
  val FrameSize = 128

  /** The encoding name for ISO-8859-1. */
  private val Encoding = "ISO-8859-1"

  /** Constant for a space character. */
  private val Space: Byte = ' '

  /** Start position of the title tag. */
  private val TitlePos = 3

  /** Length of the title tag. */
  private val TitleLen = 30

  /** Start position of the artist tag. */
  private val ArtistPos = 33

  /** Length of the artist tag. */
  private val ArtistLen = 30

  /** Start position of the album tag. */
  private val AlbumPos = 63

  /** Length of the artist tag. */
  private val AlbumLen = 30

  /** Start position of the year tag. */
  private val YearPos = 93

  /** Length of the year tag. */
  private val YearLen = 4

  /** Position of the track number tag. */
  private val TrackNoPos = 126

  /**
    * Returns an ''ID3TagProvider'' object for the specified buffer. If the
    * buffer contains a valid ID3v1 frame, the tag information is extracted and
    * can be queried from the returned provider object. Otherwise, result is
    * ''None''.
    *
    * @param tailBuffer the buffer with the ID3v1 data
    * @return an option of an ''ID3TagProvider'' for extracting tag information
    */
  def providerFor(tailBuffer: TailBuffer): Option[MetaDataProvider] = {
    val buf = tailBuffer.tail()
    if (buf.startsWith("TAG") && buf.length == FrameSize)
      Some(createProviderFromBuffer(buf))
    else None
  }

  /**
    * Extracts ID3 tags if a valid frame was detected.
    *
    * @param buf the buffer with ID3 data
    * @return an ''ID3TagProvider'' providing access to the tag values
    */
  private def createProviderFromBuffer(buf: ByteString): MetaDataProvider =
    ID3v1TagProvider(title = extractString(buf, TitlePos, TitleLen),
      artist = extractString(buf, ArtistPos, ArtistLen),
      album = extractString(buf, AlbumPos, AlbumLen),
      inceptionYearString = extractString(buf, YearPos, YearLen),
      trackNoString = extractTrackNo(buf))

  /**
    * Extracts a string value from the given byte buffer at the given position.
    * The string is encoded in ISO-8859-1, it may be padded with 0 bytes or
    * space. If data is found, an option with the trimmed string is returned.
    * Otherwise, result is ''None''.
    *
    * @param buf    the buffer with the binary data
    * @param start  the start index of the string to extract
    * @param length the maximum length of the string
    * @return an option with the extracted string
    */
  private def extractString(buf: ByteString, start: Int, length: Int): Option[String] = {
    val endIdx = start + length
    var firstNonSpace = -1
    var lastNonSpace = -1
    var pos = start

    while (pos < endIdx && buf(pos) != 0) {
      if (buf(pos) != Space) {
        lastNonSpace = pos
        if (firstNonSpace < 0) {
          firstNonSpace = pos
        }
      }
      pos += 1
    }

    if (firstNonSpace < 0) None
    else Some(buf.slice(firstNonSpace, lastNonSpace + 1).decodeString(Encoding))
  }

  /**
    * Extracts information about the track number. The track number is available
    * in ID3v1.1 only. If defined, it is located in the last byte of the
    * comment tag.
    *
    * @param buf the buffer with the binary data
    * @return an Option for the track number as string
    */
  private def extractTrackNo(buf: ByteString): Option[String] = {
    if (buf(TrackNoPos) != 0 && buf(TrackNoPos - 1) == 0) {
      val trackNo = extractByte(buf, TrackNoPos)
      Some(trackNo.toString)
    } else None
  }

  /**
    * A simple implementation of the ''ID3TagProvider'' interface based on a
    * case class.
    */
  private case class ID3v1TagProvider(title: Option[String],
                                      artist: Option[String], album: Option[String],
                                      inceptionYearString: Option[String], trackNoString:
                                      Option[String])
    extends MetaDataProvider

}
