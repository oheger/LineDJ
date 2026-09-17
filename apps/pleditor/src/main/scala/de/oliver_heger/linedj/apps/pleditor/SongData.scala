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

package de.oliver_heger.linedj.apps.pleditor

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.UriHelper

private object SongData:
  /** Constant for an unknown song duration. */
  private val UnknownDuration = -1

  /** Constant for a string to be used for an undefined property. */
  private val UndefinedProperty = ""

  /**
    * Constant for the number of characters from the media file checksum to be
    * displayed as title if no song title is available.
    */
  private val ChecksumLength = 16
end SongData

/**
  * An internally used data class that wraps a [[ArchiveModel.MediaFileInfo]]
  * object and exposes selected properties as Java Beans, so that they can be
  * displayed in a table.
  *
  * Each instance stores the ID of the media file it represents. The metadata
  * of the file need to be retrieved asynchronously; so they may not yet be
  * available. They may also be incomplete.
  *
  * @param fileID      the ID of the associated media file
  * @param optFileInfo the optional object with file information
  */
private case class SongData(fileID: String, optFileInfo: Option[ArchiveModel.MediaFileInfo]):

  import SongData.*

  /**
    * Returns the title of the associated song. This function always returns a
    * value. If the title is not available (yet), a part of the checksum is
    * shown, to have at least a unique identifier.
    *
    * @return the title of the associated song
    */
  def getTitle: String =
    optFileInfo match
      case Some(fileInfo) =>
        fileInfo.metadata.title.filter(_.nonEmpty).getOrElse(UriHelper.extractName(fileInfo.fileUri.uri))
      case None =>
        fileID.take(ChecksumLength)

  /**
    * Returns the name of the artist of the associated song. Returns an empty
    * string if the artist is unknown.
    *
    * @return the name of the artist
    */
  def getArtist: String = optFileInfo.flatMap(_.metadata.artist).getOrElse(UndefinedProperty)

  /**
    * Returns the name of the album of the associated song. Returns an empty
    * string if the album is unknown.
    *
    * @return the name of the album
    */
  def getAlbum: String = optFileInfo.flatMap(_.metadata.album).getOrElse(UndefinedProperty)

  /**
    * Returns the duration of the song (in milliseconds) or a negative value if
    * the duration is unknown.
    *
    * @return the duration of the song
    */
  def getDuration: Int = optFileInfo.flatMap(_.metadata.duration).getOrElse(UnknownDuration)
