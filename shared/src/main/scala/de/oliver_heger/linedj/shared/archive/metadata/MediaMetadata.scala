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

import de.oliver_heger.linedj.shared.RemoteSerializable

object MediaMetadata:
  /**
    * Constant for an object with undefined metadata. This can be used if 
    * metadata for a media file could not be resolved.
    */
  final val UndefinedMediaData = MediaMetadata(size = -1, checksum = "")

/**
  * A data class containing all metadata available for a media file.
  *
  * This class collects the results of metadata extraction. Because metadata
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
  * @param size              size of the media file (in bytes)
  * @param checksum          a checksum for this media file
  */
case class MediaMetadata(title: Option[String] = None,
                         artist: Option[String] = None,
                         album: Option[String] = None,
                         inceptionYear: Option[Int] = None,
                         trackNumber: Option[Int] = None,
                         duration: Option[Int] = None,
                         formatDescription: Option[String] = None,
                         size: Long,
                         checksum: String) extends RemoteSerializable
