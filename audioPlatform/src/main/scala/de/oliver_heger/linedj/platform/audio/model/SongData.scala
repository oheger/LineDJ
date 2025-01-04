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

import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata

import scala.beans.BeanProperty

object SongData:
  /** Constant for an unknown song duration. */
  val UnknownDuration: Int = -1

  /** Constant for an unknown track number. */
  val UnknownTrackNumber: Int = -1

/**
  * A data class that holds information about a song.
  *
  * The song is mainly described by its unique ID and the metadata available
  * for it. As metadata is optional, some important properties like title or
  * artist are provided explicitly. They can be resolved when an instance is
  * created and are then available for direct access.
  *
  * This class may also be used by model classes for UI controls displaying song
  * information. Therefore, it has to expose properties following the Java Beans
  * standard.
  *
  * @param id       the ID of this song in the media archive
  * @param metaData metadata about this song
  */
case class SongData(id: MediaFileID, metaData: MediaMetadata,
                    @BeanProperty title: String,
                    @BeanProperty artist: String,
                    @BeanProperty album: String):

  import SongData._

  /**
    * The duration of this song (in milliseconds) as bean property. If the
    * duration is unknown, result is less than zero.
    */
  lazy val getDuration: Int = metaData.duration getOrElse UnknownDuration

  /**
    * Returns a flag whether a duration is defined for this song in metadata.
    * If this function returns '''true''', ''getDuration'' will return a
    * correct duration value. Otherwise, a special negative value is returned.
    *
    * @return a flag whether the duration is defined for this song
    */
  def hasDuration: Boolean = metaData.duration.isDefined

  /**
    * The track number as bean property. If the track number is unknown, a
    * negative number is returned.
    */
  lazy val getTrackNumber: Int = metaData.trackNumber getOrElse UnknownTrackNumber

  /**
    * Returns a flag whether a track number is defined for this song in 
    * metadata. If this function returns '''true''', ''getTrackNumber'' will 
    * return a correct track number. Otherwise, a special negative value is 
    * returned.
    *
    * @return a flag whether the track number is defined for this song
    */
  def hasTrackNumber: Boolean = metaData.trackNumber.isDefined
