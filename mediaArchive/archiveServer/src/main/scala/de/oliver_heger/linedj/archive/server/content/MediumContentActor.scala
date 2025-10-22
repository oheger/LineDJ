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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/**
  * An object providing an internal actor implementation to manage and query
  * the content of a medium.
  *
  * The archive content manager actor creates an instance of this actor class 
  * for every medium in the archive. An instance stores the (metadata about 
  * the) songs on this medium and allows querying them based on different
  * groupings. Each support grouping is provided by a dedicated
  * [[MediumContentManager]] instance. This allows creating the corresponding
  * views lazily, when there is actually a request.
  */
private object MediumContentActor:
  /**
    * Type alias for the commands supported by this actor implementation. The
    * actor supports adding song metadata for the represented medium and all 
    * kinds of commands to query the data on this medium.
    */
  type MediumContentCommand = MediaMetadata | ArchiveCommands.ReadMediumContentCommand

  /**
    * A data class to hold the different content managers used by an actor
    * instance. This is part of the actor's state.
    *
    * @param artists view for the artists on this medium
    * @param albums  view for the albums of this medium
    */
  private case class ContentManagers(artists: MediumContentManager[ArchiveModel.ArtistInfo],
                                     albums: MediumContentManager[ArchiveModel.AlbumInfo]):
    /**
      * Notifies all managed content managers about a change in the list of
      * available songs.
      *
      * @param songs the update list of song data
      */
    def updateSongs(songs: Iterable[MediaMetadata]): Unit =
      artists.update(songs)
      albums.update(songs)
  end ContentManagers

  /**
    * Returns the behavior of a new actor instance.
    *
    * @return the behavior to create a new instance
    */
  def apply(): Behavior[MediumContentCommand] =
    handleCommand(createManagers(), Nil)

  /**
    * The main command handler function of this actor implementation.
    *
    * @param managers the object with content managers
    * @param songs    the current list of songs on this medium
    * @return the updated behavior
    */
  private def handleCommand(managers: ContentManagers, songs: List[MediaMetadata]): Behavior[MediumContentCommand] =
    Behaviors.receiveMessage:
      case song: MediaMetadata =>
        val nextSongs = song :: songs
        managers.updateSongs(nextSongs)
        handleCommand(managers, nextSongs)

      case req@ArchiveCommands.ReadMediumContentCommand.GetArtists(_, replyTo) =>
        val artists = managers.artists("")
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, artists)
        Behaviors.same

      case req@ArchiveCommands.ReadMediumContentCommand.GetAlbums(_, replyTo) =>
        val albums = managers.albums("")
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, albums)
        Behaviors.same

  /**
    * Creates a [[ContentManagers]] object with all the managers to construct
    * the supported views of data.
    *
    * @return the object with all managers
    */
  private def createManagers(): ContentManagers =
    import MediumContentManager.given
    ContentManagers(
      artists = MediumContentManager(
        idPrefix = "art",
        keyExtractor = _.artist,
        dataExtractor = (id, data) => ArchiveModel.ArtistInfo(id, data.artist.getOrElse("")),
        groupingFunc = _ => ""
      ),
      albums = MediumContentManager(
        idPrefix = "alb",
        keyExtractor = _.album,
        dataExtractor = (id, data) => ArchiveModel.AlbumInfo(id, data.album.getOrElse("")),
        groupingFunc = _ => ""
      )
    )
