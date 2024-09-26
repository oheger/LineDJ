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

import de.oliver_heger.linedj.io.parser.*
import de.oliver_heger.linedj.platform.audio.SetPlaylist
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistModel.*
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

import scala.annotation.tailrec

/**
  * A module for parsing files with a JSON representation of a playlist.
  *
  * A playlist file consists of an array of JSON objects; each object
  * represents a song in the playlist and contains a number of identifying
  * properties.
  */
object PersistentPlaylistParser:
  /** Property for the playlist index. */
  val PropIndex = "index"

  /** Property for the URI of the medium. */
  val PropMediumURI = "mediumURI"

  /** Property for the description path of the medium. */
  val PropMediumDescPath = "mediumDescriptionPath"

  /** Property for the checksum of the medium. */
  val PropMediumChecksum = "mediumChecksum"

  /** Property for the component ID of the managing archive. */
  val PropArchiveCompID = "archiveComponentID"

  /** Property for the song URI. */
  val PropURI = "uri"

  /**
    * Returns a [[Source]] that extracts [[PlaylistItem]] objects from the 
    * given data source.
    *
    * @param source the source to be parsed
    * @return a [[Source]] for extracting [[PlaylistItem]] objects
    */
  def parsePlaylist(source: Source[ByteString, Any]): Source[PlaylistItemData, Any] =
    JsonStreamParser.parseStream[PersistentPlaylistItemData, Any](source)
      .map(convertToDataModel)

  /**
    * Generates a ''Playlist'' from the given list of intermediate playlist
    * items. The list of songs is sorted, invalid items are filtered out, and
    * the current position is applied.
    *
    * @param items the list of playlist items
    * @return the resulting ''Playlist''
    */
  def generateFinalPlaylist(items: List[PlaylistItemData], position: CurrentPlaylistPosition): SetPlaylist =
    applyPosition(items.sortWith(_.index < _.index), position)

  /**
    * Creates a playlist by applying position information to the given list
    * of indexed songs.
    *
    * @param items    the list of songs and their indices
    * @param position position information
    * @return the resulting ''Playlist''
    */
  private def applyPosition(items: List[PlaylistItemData],
                            position: CurrentPlaylistPosition): SetPlaylist =
    @tailrec def splitAndConvert(currentItems: List[PlaylistItemData],
                                 played: PlaylistService.SongList): SetPlaylist =
      currentItems match
        case h :: t =>
          if h.index >= position.index then
            applyOffsets(Playlist(playedSongs = played,
              pendingSongs = h.fileID :: (t map (_.fileID))), h.index, position)
          else splitAndConvert(t, h.fileID :: played)

        case _ =>
          SetPlaylist(Playlist(playedSongs = played, pendingSongs = Nil), closePlaylist = played.nonEmpty)

    splitAndConvert(items, Nil)

  /**
    * Transforms a ''Playlist'' to a ''SetPlaylist'' command by applying
    * position information if applicable. Position and time offsets are set
    * only if the song with the current index matches.
    *
    * @param pl       the ''Playlist''
    * @param curIdx   the index of the current song in the playlist
    * @param position the ''CurrentPlaylistPosition''
    * @return the command for setting the playlist
    */
  private def applyOffsets(pl: Playlist, curIdx: Int, position: CurrentPlaylistPosition):
  SetPlaylist =
    if curIdx == position.index then
      SetPlaylist(playlist = pl, positionOffset = position.positionOffset,
        timeOffset = position.timeOffset)
    else SetPlaylist(playlist = pl)
