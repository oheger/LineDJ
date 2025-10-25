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

package de.oliver_heger.linedj.archive.server.model

import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor.typed.ActorRef

/**
  * A module that defines different command classes for the interaction with
  * actors that are used by the implementation of the archive server.
  *
  * There is no direct 1:1 relationship between command classes and actors.
  * Typically, a single actor can implement multiple command classes. Different
  * clients of the actor use different commands depending on the use cases they
  * require. Therefore, this separation into multiple command classes makes 
  * sense to make sure that a client can only interact with an actor using the
  * commands it actually needs.
  */
object ArchiveCommands:
  /**
    * Type alias for the command type for querying various  information about
    * the media archive.
    */
  type ArchiveQueryCommand = ArchiveCommands.ReadArchiveContentCommand | ArchiveCommands.ReadMediumContentCommand

  /**
    * A class defining a set of commands to query the content of the archive.
    * These commands deal with the archive as a whole; they therefore support
    * querying on the granularity of media (i.e. querying all media or the
    * details of a single one).
    */
  enum ReadArchiveContentCommand:
    /**
      * A command to query overview information about the currently available
      * media. As a response, the actor sends a [[GetMediaResponse]] message.
      *
      * @param replyTo the reference to the actor to receive the response
      */
    case GetMedia(replyTo: ActorRef[GetMediaResponse])

    /**
      * A command to query detail information for a specific medium identified
      * by its ID. As a response, the actor sends a [[GetMediumResponse]]
      * message.
      *
      * @param id      the ID of the desired medium
      * @param replyTo the reference to the actor to receive the response
      */
    case GetMedium(id: Checksums.MediumChecksum,
                   replyTo: ActorRef[GetMediumResponse])
  end ReadArchiveContentCommand

  /**
    * A class defining the commands to update the content of the archive. Such
    * commands are generated and sent to the content actor during processing of
    * the audio files in local archives.
    */
  enum UpdateArchiveContentCommand:
    /**
      * A command to add a medium to the in-memory representation of the 
      * archive.
      *
      * @param medium the data about the medium to be added
      */
    case AddMedium(medium: ArchiveModel.MediumDetails)

    /**
      * A command to add information about a specific media file (a song) to 
      * the archive.
      *
      * @param mediumID the ID of the medium the file belongs to
      * @param metadata metadata about this file
      */
    case AddMediaFile(mediumID: Checksums.MediumChecksum,
                      metadata: MediaMetadata)
  end UpdateArchiveContentCommand

  /**
    * A class defining the command to query the content of a specific medium.
    * These commands allow obtaining different views on the songs contained on
    * a medium. It is also possible to query information about the artists and
    * albums that are referenced by songs.
    */
  enum ReadMediumContentCommand:
    /**
      * A command to request information about all the artists found on a
      * specific medium.
      *
      * @param mediumID the ID of the desired medium
      * @param replyTo  the reference to the actor to receive the response
      */
    case GetArtists(mediumID: Checksums.MediumChecksum,
                    replyTo: ActorRef[GetMediumDataResponse[ArchiveModel.ArtistInfo]])

    /**
      * A command to request information about all the albums found on a 
      * specific medium.
      *
      * @param mediumID the ID of the desired medium
      * @param replyTo  the reference to the actor to receive the response
      */
    case GetAlbums(mediumID: Checksums.MediumChecksum,
                   replyTo: ActorRef[GetMediumDataResponse[ArchiveModel.AlbumInfo]])

    /**
      * A command to request information about the albums released by a 
      * specific artist on a given medium. The artist needs to be referenced
      * via the ID contained in the data retrieved via the [[GetArtists]]
      * command.
      *
      * @param mediumID the ID of the desired
      * @param artistID the ID of the artist in question
      * @param replyTo  the reference to the actor to receive the response
      */
    case GetAlbumsForArtist(mediumID: Checksums.MediumChecksum,
                            artistID: String,
                            replyTo: ActorRef[GetMediumDataResponse[ArchiveModel.AlbumInfo]])

    /**
      * A command to request information about all songs belonging to a
      * specific artist. The artist needs to be referenced via the ID contained
      * in the data retrieved via the [[GetArtists]] command.
      *
      * @param mediumID the ID of the desired medium
      * @param artistID the ID of the artist in question
      * @param replyTo  the reference to the actor to receive the response
      */
    case GetSongsForArtist(mediumID: Checksums.MediumChecksum,
                           artistID: String,
                           replyTo: ActorRef[GetMediumDataResponse[MediaMetadata]])

    /**
      * A command to request information about all songs belonging to a
      * specific album. The album needs to be referenced via the ID contained
      * in the data retrieved via the [[GetAlbums]] command.
      *
      * @param mediumID the ID of the desired medium
      * @param albumID  the ID of the album in question
      * @param replyTo  the reference to the actor to receive the response
      */
    case GetSongsForAlbum(mediumID: Checksums.MediumChecksum,
                          albumID: String,
                          replyTo: ActorRef[GetMediumDataResponse[MediaMetadata]])
  end ReadMediumContentCommand

  /**
    * A data class representing the response sent for a
    * [[ReadArchiveContentCommand.GetMedia]] command.
    *
    * @param media a list with the data about all media
    */
  case class GetMediaResponse(media: List[ArchiveModel.MediumOverview])

  /**
    * A data class representing the response sent for a 
    * [[ReadArchiveContentCommand.GetMedium]] command. Since the ID passed in the
    * request may be invalid, the response contains an [[Option]] with details;
    * it is ''None'' if the ID could not be resolved.
    *
    * @param id         the ID of the requested medium
    * @param optDetails the optional details of this medium
    */
  case class GetMediumResponse(id: Checksums.MediumChecksum,
                               optDetails: Option[ArchiveModel.MediumDetails])

  /**
    * A data class representing the response sent for requests of type
    * [[ReadMediumContentCommand]]. The concrete results depend on the request.
    * Therefore, this class has a type parameter. Requests for the content of a
    * medium can fail if they contain an invalid ID. This is reflected in this
    * type by using an [[Option]] for results; it is ''None'' if the ID from
    * the request could not be resolved.
    *
    * @param request   the request this response is for
    * @param optResult the optional result data
    * @tparam DATA the type of the data in the result
    */
  case class GetMediumDataResponse[DATA](request: ReadMediumContentCommand,
                                         optResult: Option[List[DATA]])
