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

import de.oliver_heger.linedj.archive.server.content.MediumContentManagerActor.MediumContentManagerCommand
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}

import scala.concurrent.{ExecutionContext, Future}

/**
  * An object providing an internal actor implementation to manage and query
  * the content of a medium.
  *
  * The archive content manager actor creates an instance of this actor class 
  * for every medium in the archive. An instance stores the (metadata about 
  * the) songs on this medium and allows querying them based on different
  * groupings. Each supported grouping is provided by a dedicated
  * [[MediumContentManagerActor]] instance. This allows creating the 
  * corresponding views lazily, when there is actually a request.
  */
private object MediumContentActor:
  /**
    * Type alias for the commands supported by this actor implementation. The
    * actor supports adding song metadata for the represented medium and all 
    * kinds of commands to query the data on this medium.
    */
  type MediumContentCommand = MediaMetadata | ArchiveCommands.ReadMediumContentCommand

  /** [[KeyExtractor]] function to obtain a song's artist. */
  private val ArtistKeyExtractor: MediumContentManagerActor.KeyExtractor = _.artist

  /** [[KeyExtractor]] function to obtain a song's album. */
  private val AlbumKeyExtractor: MediumContentManagerActor.KeyExtractor = _.album

  /**
    * The transformer function to deduplicate the artist mapping of a medium.
    */
  private val DeduplicateArtistTransformer:
    MediumContentManagerActor.DataTransformer[ArchiveModel.ArtistInfo, ArchiveModel.ArtistInfo] =
    deduplicateTransformer(_.id)

  /**
    * The transformer function to deduplicate the album mapping of a medium.
    */
  private val DeduplicateAlbumTransformer:
    MediumContentManagerActor.DataTransformer[ArchiveModel.AlbumInfo, ArchiveModel.AlbumInfo] =
    deduplicateTransformer(_.id)

  /**
    * A data class to hold the different content manager actors used by an
    * actor instance. This is part of the actor's state.
    *
    * @param artists        view for the artists on this medium
    * @param albums         view for the albums of this medium
    * @param albumsByArtist view for the albums of a specific artist
    * @param songsByArtist  view for the songs of a specific artist
    * @param songsByAlbum   view for the songs of a specific album
    */
  private case class ContentManagers(artists: ActorRef[MediumContentManagerCommand[ArchiveModel.ArtistInfo]],
                                     albums: ActorRef[MediumContentManagerCommand[ArchiveModel.AlbumInfo]],
                                     albumsByArtist: ActorRef[MediumContentManagerCommand[ArchiveModel.AlbumInfo]],
                                     songsByArtist: ActorRef[MediumContentManagerCommand[MediaMetadata]],
                                     songsByAlbum: ActorRef[MediumContentManagerCommand[MediaMetadata]]):
    /**
      * Notifies all managed content manager actors about a change in the list 
      * of available songs.
      *
      * @param songs the update list of song data
      */
    def updateSongs(songs: Iterable[MediaMetadata]): Unit =
      artists ! MediumContentManagerCommand.UpdateData(songs)

      val albumCommand = MediumContentManagerCommand.UpdateData[ArchiveModel.AlbumInfo](songs)
      albums ! albumCommand
      albumsByArtist ! albumCommand

      val songsCommand = MediumContentManagerCommand.UpdateData[MediaMetadata](songs)
      songsByArtist ! songsCommand
      songsByAlbum ! songsCommand
  end ContentManagers

  /**
    * Returns the behavior of a new actor instance.
    *
    * @param mediumID        the ID of the medium
    * @param artistIdManager the ID manager that manages artist IDs
    * @param albumIdManager  the ID manager that manages album IDs
    * @return the behavior to create a new instance
    */
  def apply(mediumID: String,
            artistIdManager: ActorRef[IdManagerActor.QueryIdCommand],
            albumIdManager: ActorRef[IdManagerActor.QueryIdCommand]): Behavior[MediumContentCommand] =
    Behaviors.setup[MediumContentCommand]: context =>

      /**
        * A transformation function that constructs the final result for the
        * ''albumsForArtists'' view.
        *
        * @param mapping the temporary mapping
        * @return the transformed mapping
        */
      def albumForArtistTransformer(mapping: Map[String, List[Option[String]]]):
      Future[Map[String, List[ArchiveModel.AlbumInfo]]] =
        given Scheduler = context.system.scheduler

        given ExecutionContext = context.system.executionContext

        val albumNames = mapping.values.flatten
        albumIdManager.getIds(albumNames).map: idResponse =>
          mapping.map: (key, albumNames) =>
            (key, albumNames.map(name => ArchiveModel.AlbumInfo(idResponse.ids(name), name.getOrElse(""))))
        .flatMap(DeduplicateAlbumTransformer)

      /**
        * Generates the name of the content actor managing a specific view.
        *
        * @param view the name of the view
        * @return the name of the corresponding content actor
        */
      def contentName(view: String): String = s"$mediumID.$view"

      /**
        * Creates a [[ContentManagers]] object with all the managers to construct
        * the supported views of data.
        *
        * @return the object with all managers
        */
      def createManagers(): ContentManagers =
        import MediumContentManagerActor.given
        ContentManagers(
          artists = context.spawn(
            MediumContentManagerActor.newTransformingInstance(
              keyExtractor = ArtistKeyExtractor,
              dataExtractor = (id, data) => ArchiveModel.ArtistInfo(id, extractArtistName(data)),
              groupingFunc = MediumContentManagerActor.AggregateGroupingFunc,
              transformer = DeduplicateArtistTransformer,
              idManager = artistIdManager
            ),
            contentName("artists")
          ),
          albums = context.spawn(
            MediumContentManagerActor.newTransformingInstance(
              keyExtractor = AlbumKeyExtractor,
              dataExtractor = (id, data) => ArchiveModel.AlbumInfo(id, extractAlbumName(data)),
              groupingFunc = MediumContentManagerActor.AggregateGroupingFunc,
              transformer = DeduplicateAlbumTransformer,
              idManager = albumIdManager
            ),
            contentName("albums")
          ),
          albumsByArtist = context.spawn(
            MediumContentManagerActor.newTransformingInstance(
              keyExtractor = ArtistKeyExtractor,
              dataExtractor = (_, metadata) => metadata.album,
              transformer = albumForArtistTransformer,
              idManager = artistIdManager
            ),
            contentName("albumsByArtist")
          ),
          songsByArtist = context.spawn(
            MediumContentManagerActor.newInstance(
              keyExtractor = ArtistKeyExtractor,
              dataExtractor = MediumContentManagerActor.MetadataExtractor,
              idManager = artistIdManager
            ),
            contentName("songsByArtist")
          ),
          songsByAlbum = context.spawn(
            MediumContentManagerActor.newInstance(
              keyExtractor = AlbumKeyExtractor,
              dataExtractor = MediumContentManagerActor.MetadataExtractor,
              idManager = albumIdManager
            ),
            contentName("songsByAlbum")
          )
        )

      val managers = createManagers()

      /**
        * The main command handler function of this actor implementation.
        *
        * @param songs the current list of songs on this medium
        * @return the updated behavior
        */
      def handleCommand(songs: List[MediaMetadata]): Behavior[MediumContentCommand] =
        Behaviors.receiveMessage:
          case song: MediaMetadata =>
            val nextSongs = song :: songs
            managers.updateSongs(nextSongs)
            handleCommand(nextSongs)

          case req@ArchiveCommands.ReadMediumContentCommand.GetArtists(_, replyTo) =>
            managers.artists ! MediumContentManagerCommand.GetDataFor(
              id = MediumContentManagerActor.AggregateGroupingKey,
              request = req,
              replyTo = replyTo
            )
            Behaviors.same

          case req@ArchiveCommands.ReadMediumContentCommand.GetAlbums(_, replyTo) =>
            managers.albums ! MediumContentManagerCommand.GetDataFor(
              id = MediumContentManagerActor.AggregateGroupingKey,
              request = req,
              replyTo = replyTo
            )
            Behaviors.same

          case req@ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(_, artistID, replyTo) =>
            managers.albumsByArtist ! MediumContentManagerCommand.GetDataFor(artistID, req, replyTo)
            Behaviors.same

          case req@ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(_, artistID, replyTo) =>
            managers.songsByArtist ! MediumContentManagerCommand.GetDataFor(artistID, req, replyTo)
            Behaviors.same

          case req@ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(_, albumID, replyTo) =>
            managers.songsByAlbum ! MediumContentManagerCommand.GetDataFor(albumID, req, replyTo)
            Behaviors.same

      handleCommand(Nil)

  /**
    * Extracts the name of the artist of the given song data. Handles an
    * undefined artist.
    *
    * @param data the metadata of the current song
    * @return the name of the artist of this song
    */
  private def extractArtistName(data: MediaMetadata): String = data.artist.getOrElse("")

  /**
    * Extracts the name of the album of the given song data. Handles an
    * undefined album.
    *
    * @param data the metadata of the current song
    * @return the name of the album of this song
    */
  private def extractAlbumName(data: MediaMetadata): String = data.album.getOrElse("")

  /**
    * Performs a deduplication of entries in a list. This function is needed
    * for lists of artist and album information where names with different
    * spelling are mapped to the same IDs. Then the lists contain multiple
    * entries with the same ID, but different names. The function processes the
    * list, so that all IDs are unique (choosing a random element).
    *
    * @param items  the list to be deduplicated
    * @param idFunc the function to extract the ID of an item
    * @tparam A the type of the items in the list
    * @return the deduplicated list
    */
  private def deduplicate[A](items: List[A])(idFunc: A => String): List[A] =
    val idMap = items.map(i => (idFunc(i), i)).toMap
    if idMap.size < items.size then
      val remainingItems = idMap.values.toSet
      items.filter(remainingItems.contains)
    else
      items

  /**
    * A transformer function that performs a deduplication of a mapping view
    * using the [[deduplicate]] function.
    *
    * @param idFunc  the function to extract the ID of an item
    * @param mapping the mapping to be deduplicated
    * @tparam A the type of the items in the mapping
    * @return the deduplicated mapping
    */
  private def deduplicateTransformer[A](idFunc: A => String)(mapping: Map[String, List[A]]):
  Future[Map[String, List[A]]] =
    Future.successful(
      mapping.map(e => (e._1, deduplicate(e._2)(idFunc)))
    )
