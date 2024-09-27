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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.platform.audio.SetPlaylist
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import spray.json.DefaultJsonProtocol.*
import spray.json.*

/**
  * A module defining the data model for storing playlist information.
  *
  * The module also provides functionality for JSON serialization.
  */
object PersistentPlaylistModel:
  /**
    * A data class defining the content of an item in the playlist.
    *
    * @param index  the index of this item
    * @param fileID the ID identifying the song file in the archive
    */
  case class PlaylistItemData(index: Int,
                              fileID: MediaFileID)

  /**
    * A data class defining the persistent form of a playlist item. When
    * persisting or loading a playlist, conversion to the in-memory data model
    * takes place.
    *
    * @param index                 the index of this item in the playlist
    * @param mediumURI             the URI of the whole medium
    * @param mediumDescriptionPath the path to the medium description file
    * @param mediumChecksum        the checksum of the medium
    * @param archiveComponentID    the ID of the owning archive component
    * @param uri                   the URI of the song file
    */
  case class PersistentPlaylistItemData(index: Int,
                                        mediumURI: String,
                                        mediumDescriptionPath: Option[String],
                                        mediumChecksum: Option[String],
                                        archiveComponentID: String,
                                        uri: String)

  /**
    * A class representing the current position in a playlist.
    *
    * The position consists of the index of the current song which is played,
    * and information about the part of the song that has been played already.
    *
    * @param index    the index of the current song in the playlist (0-based)
    * @param position the position (in bytes) within the current song
    * @param time     the time (in millis) within the current song
    */
  case class CurrentPlaylistPosition(index: Int, position: Long, time: Long)

  /**
    * A class representing a playlist that has been loaded from
    * [[LoadPlaylistActor]].
    *
    * @param setPlaylist the command for setting the playlist
    */
  case class LoadedPlaylist(setPlaylist: SetPlaylist)

  /** A format for parsing persistent playlist items. */
  given RootJsonFormat[PersistentPlaylistItemData] = jsonFormat6(PersistentPlaylistItemData.apply)

  /** A format for parsing the playlist position. */
  given RootJsonFormat[CurrentPlaylistPosition] = jsonFormat3(CurrentPlaylistPosition.apply)

  /**
    * Converts the given persistent item to its in-memory form.
    *
    * @param data the playlist data item to convert
    * @return the in-memory model representation of this item
    */
  def convertToDataModel(data: PersistentPlaylistItemData): PlaylistItemData =
    PlaylistItemData(
      index = data.index,
      fileID = MediaFileID(
        mediumID = MediumID(
          mediumURI = data.mediumURI,
          mediumDescriptionPath = data.mediumDescriptionPath,
          archiveComponentID = data.archiveComponentID
        ),
        uri = data.uri,
        checksum = data.mediumChecksum
      )
    )

  /**
    * Converts the given playlist item model object to its persistent form, so
    * that it can be stored on disk.
    *
    * @param data the model object to convert
    * @return the representation of this item for persistence
    */
  def convertToPersistentModel(data: PlaylistItemData): PersistentPlaylistItemData =
    PersistentPlaylistItemData(
      index = data.index,
      mediumURI = data.fileID.mediumID.mediumURI,
      mediumDescriptionPath = data.fileID.mediumID.mediumDescriptionPath,
      mediumChecksum = data.fileID.checksum,
      archiveComponentID = data.fileID.mediumID.archiveComponentID,
      uri = data.fileID.uri
    )
