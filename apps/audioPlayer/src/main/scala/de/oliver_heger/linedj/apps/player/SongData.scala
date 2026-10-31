/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.player

import de.oliver_heger.linedj.apps.player.SongData.{UnknownDuration, given_Conversion_Option_String}
import de.oliver_heger.linedj.archive.server.model.ArchiveModel

private object SongData:
  /**
    * Constant for a duration value to be returned for an unknown duration.
    * Negative values are handled correctly by the duration transformer.
    */
  private val UnknownDuration: Int = -1

  /** Constant to be displayed for undefined string properties. */
  private val UnknownString = "-"

  /**
    * Converts an optional string to a string to be displayed in the UI. If the
    * [[Option]] is undefined, a placeholder is returned.
    */
  given Conversion[Option[String], String] = _.getOrElse(UnknownString)
end SongData

/**
  * A data class that is used to represent the songs in the UI table showing
  * the current playlist. The actual data comes from a wrapped
  * [[ArchiveModel.MediaFileInfo]] object; this class provides accessors, so 
  * that the relevant properties can be accessed by the table.
  *
  * @param fileInfo the object with media file information
  */
private case class SongData(fileInfo: ArchiveModel.MediaFileInfo):
  /**
    * Returns the title of this song.
    *
    * @return the song title
    */
  def getTitle: String = fileInfo.metadata.title

  /**
    * Returns the artist of this song.
    *
    * @return the artist
    */
  def getArtist: String = fileInfo.metadata.artist

  /**
    * Returns the album of this song.
    *
    * @return the album
    */
  def getAlbum: String = fileInfo.metadata.album

  /**
    * Returns the track number of this song.
    *
    * @return the track number
    */
  def getTrackNumber: String = fileInfo.metadata.trackNumber.map(_.toString)

  /**
    * Returns the inception year of this song.
    *
    * @return the inception year
    */
  def getInceptionYear: String = fileInfo.metadata.inceptionYear.map(_.toString)

  /**
    * Returns the duration of this song in seconds, to be further processed by
    * a duration formatter. If the duration is undefined, result is a negative
    * value.
    *
    * @return the duration of the song
    */
  def getDuration: Int = fileInfo.metadata.duration.getOrElse(UnknownDuration)
