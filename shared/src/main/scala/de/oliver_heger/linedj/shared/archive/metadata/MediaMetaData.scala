/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.shared.RemoteSerializable

/**
  * A data class containing all meta data available for a media file.
  *
  * This class collects the results of meta data extraction. Because meta data
  * is optional, all properties are modelled as options.
  *
  * @param title             optional title of the media file
  * @param artist            optional artist performing this song
  * @param album             optional album of this media file
  * @param inceptionYear     optional year of the recording
  * @param trackNumber       optional track number on the contained album
  * @param duration          optional duration of this media file (in milliseconds)
  * @param formatDescription optional format description; this is specific to
  *                          the format of the media file
  * @param size          optional size of the media file (in bytes)
  */
case class MediaMetaData(title: Option[String] = None,
                         artist: Option[String] = None,
                         album: Option[String] = None,
                         inceptionYear: Option[Int] = None,
                         trackNumber: Option[Int] = None,
                         duration: Option[Int] = None,
                         formatDescription: Option[String] = None,
                         size: Option[Long] = None) extends RemoteSerializable:
  /**
    * Returns the size of the media file (in bytes) as a ''Long''. Result is 0
    * if the size is unknown.
    *
    * @return the size of the media file in bytes
    */
  def fileSize: Long = size.getOrElse(0)
