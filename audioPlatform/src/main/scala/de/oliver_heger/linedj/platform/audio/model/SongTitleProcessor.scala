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

package de.oliver_heger.linedj.platform.audio.model

import de.oliver_heger.linedj.shared.archive.media.UriHelper

/**
  * A trait for processing the title of a song that was generated by an
  * [[UnknownPropertyResolver]].
  *
  * If the title of a song is not defined in its metadata, an instance of
  * [[UnknownPropertyResolver]] is used to generate a title. Per default, the
  * song's URI (as string) is used for this purpose. This trait allows
  * executing more processing steps to come to a better song title.
  *
  * Basically, this trait defines a transformation from a song title to
  * another title. There is a set of default transformations that are already
  * implemented by classes in the platform. Custom implementations can be added
  * easily.
  *
  * A concrete ''UnknownPropertyResolver'' can be configured with a collection
  * of ''SongTitleProcessor'' objects. They are then applied (in order) to the
  * song's URI, so that a more meaningful title is constructed.
  */
trait SongTitleProcessor:
  /**
    * Processes the title of a song.
    *
    * @param title the original title
    * @return the new title
    */
  def processTitle(title: String): String

/**
  * A ''SongTitleProcessor'' implementation that extracts the last path
  * component from the passed in URI string and returns this as song title.
  *
  * The implementation can deal with URIs or file paths, i.e. both slash or
  * backslash are accepted as path separators. The last component in the path
  * is interpreted as file name and returned as song title. If the passed in
  * string does not contain any path separators, it is returned without
  * changes.
  */
object SongTitlePathProcessor extends SongTitleProcessor:
  override def processTitle(title: String): String =
    UriHelper.extractName(UriHelper.normalize(title))

/**
  * A ''SongTitleProcessor'' implementation that removes a file extension from
  * the song title if it is present.
  *
  * This processor should be applied after the last path component of the song
  * URI has been extracted. It searches for the last dot in the remaining
  * string. If it is found, the string is cut at this position.
  */
object SongTitleExtensionProcessor extends SongTitleProcessor:
  override def processTitle(title: String): String =
    UriHelper removeExtension title

/**
  * A ''SongTitleProcessor'' implementation that performs URL-decoding on a
  * song title if necessary.
  *
  * This implementation tries to figure out whether the passed in string is
  * URL-encoded. If this seems to be the case (if it contains '%' characters
  * with valid codes), the decoding is applied.
  */
object SongTitleDecodeProcessor extends SongTitleProcessor:
  override def processTitle(title: String): String = UriHelper urlDecode title


object SongTitleRemoveTrackProcessor:
  /** RegEx to parse for numbers at the beginning of the title. */
  private val RegExTrackNumber =
    """((\d+)[\s.-]+).+""".r

  /**
    * Converts a string with the track number to an integer. Handles overflows
    * and number format exceptions by returning the maximum integer number;
    * this should prevent that this number is removed from the song title.
    *
    * @param no the number as string
    * @return the numeric track number
    */
  private def trackNoToInt(no: String): Int = try
    no.toInt
  catch
    case _: NumberFormatException => Integer.MAX_VALUE

/**
  * A ''SongTitleProcessor'' implementation that tries to remove a leading
  * track number from a song title.
  *
  * The class checks whether the song title starts with a number and an
  * optional separator character. If so, this part is removed from the title.
  * It is possible to configure a maximum track number; this can be used to
  * prevent that ''99 Air balloons'' gets modified.
  *
  * @param maxTrack the maximum track number
  */
class SongTitleRemoveTrackProcessor(val maxTrack: Int) extends SongTitleProcessor:

  import SongTitleRemoveTrackProcessor._

  /**
    * Processes the title of a song.
    *
    * @param title the original title
    * @return the new title
    */
  override def processTitle(title: String): String =
    title match
      case RegExTrackNumber(trackPrefix, trackNo) if trackNoToInt(trackNo) <= maxTrack =>
        title drop trackPrefix.length
      case _ => title
