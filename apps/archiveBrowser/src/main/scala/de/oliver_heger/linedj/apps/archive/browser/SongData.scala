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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata

private object SongData:
  /** Constant for an unknown song duration. */
  private val UnknownDuration = -1

  /** Constant for an unknown track number. */
  private val UnknownTrackNumber = -1

  /** Constant for a string to be used for an undefined property. */
  private val UndefinedProperty = ""
end SongData

/**
  * A data class to be used in the table models to display songs.
  *
  * An instance wraps a [[MediaMetadata]] object that holds the actual
  * information about the song. The relevant properties are exposed via
  * properties following the Java beans specification.
  *
  * @param metadata metadata about this song
  */
private case class SongData(metadata: MediaMetadata):

  import SongData.*

  /**
    * Returns the title of this song. If the title is undefined in the
    * metadata, result is an empty string.
    *
    * @return the song title
    */
  def getTitle: String = metadata.title.getOrElse(UndefinedProperty)

  /**
    * Returns the artist of this song. If the artist is undefined in the
    * metadata, result is an empty string.
    *
    * @return the artist of this song
    */
  def getArtist: String = metadata.artist.getOrElse(UndefinedProperty)

  /**
    * Returns the duration of this song (in seconds) as bean property. If
    * the duration is unknown, result is less than zero, which is handled by
    * the duration transformer.
    *
    * @return the duration of this song
    */
  def getDuration: Int = metadata.duration getOrElse UnknownDuration

  /**
    * Returns the track number as bean property. If the track number is
    * unknown, a negative number is returned.
    *
    * @return the track number of this song
    */
  def getTrackNumber: Int = metadata.trackNumber getOrElse UnknownTrackNumber
