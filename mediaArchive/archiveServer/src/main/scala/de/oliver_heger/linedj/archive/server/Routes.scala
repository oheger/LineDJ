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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshaller
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import org.apache.pekko.util.Timeout

/**
  * An object defining the routes supported by the archive server.
  *
  * The routes allow accessing the content of the archive in various ways.
  */
object Routes extends ArchiveModel.ArchiveJsonSupport:
  /**
    * Returns the top-level route of the archive server.
    *
    * @param config       the configuration for the server
    * @param contentActor the actor managing the content of the archive
    * @return the top-level route of the server
    */
  def route(config: ArchiveServerConfig, contentActor: ActorRef[ArchiveCommands.ArchiveQueryCommand])
           (using system: classics.ActorSystem): Route =
    given ActorSystem[Nothing] = system.toTyped

    given Timeout(config.timeout)
    import org.apache.pekko.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem

    /** The route to query all known media. */
    val mediaRoute = get:
      val futMediaOverview = contentActor.ask[ArchiveCommands.GetMediaResponse](
        ArchiveCommands.ReadArchiveContentCommand.GetMedia(_)
      )
      onSuccess(futMediaOverview): mediaResponse =>
        complete(ArchiveModel.MediaOverview(mediaResponse.media))

    /**
      * Handles a request for data of a specific medium. The data is queried
      * from the content handler actor. Invalid parameters lead to a 404
      * response.
      *
      * @param mediumID   the ID of the medium in question
      * @param cmd        a function to create the command to the actor
      * @param marshaller an implicit marshaller for the result type
      * @tparam DATA the type of the result
      * @return the query route
      */
    def handleMediumQuery[DATA](mediumID: String)
                               (cmd: ActorRef[ArchiveCommands.GetMediumDataResponse[DATA]] =>
                                 ArchiveCommands.ArchiveQueryCommand)
                               (using marshaller: ToResponseMarshaller[ArchiveModel.ItemsResult[DATA]]): Route =
      val futResponse =
        contentActor.ask[ArchiveCommands.GetMediumDataResponse[DATA]](cmd)
      onSuccess(futResponse): response =>
        response.optResult match
          case Some(result) =>
            complete(ArchiveModel.ItemsResult(result))
          case None =>
            complete(StatusCodes.NotFound)

    /**
      * Returns the route to query details of a medium.
      *
      * @param mediumID the ID of the medium
      * @return the medium details route
      */
    def mediumDetailsRoute(mediumID: String): Route =
      get:
        val futMediumDetails = contentActor.ask[ArchiveCommands.GetMediumResponse](ref =>
          ArchiveCommands.ReadArchiveContentCommand.GetMedium(Checksums.MediumChecksum(mediumID), ref)
        )
        onSuccess(futMediumDetails): mediumResponse =>
          mediumResponse.optDetails match
            case Some(details) =>
              complete(details)
            case None =>
              complete(StatusCodes.NotFound)

    /**
      * Returns routes to query information about the artists of a medium.
      *
      * @param mediumID the ID of the medium
      * @return the route
      */
    def mediumArtistsRoutes(mediumID: String): Route =
      concat(
        pathEnd:
          get:
            handleMediumQuery(mediumID): replyTo =>
              ArchiveCommands.ReadMediumContentCommand.GetArtists(Checksums.MediumChecksum(mediumID), replyTo),
        pathPrefix(Segment): artistID =>
          concat(
            path("songs"):
              get:
                handleMediumQuery[MediaMetadata](mediumID): replyTo =>
                  ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(
                    mediumID = Checksums.MediumChecksum(mediumID),
                    artistID = artistID,
                    replyTo = replyTo
                  ),
            path("albums"):
              get:
                handleMediumQuery[ArchiveModel.AlbumInfo](mediumID): replyTo =>
                  ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(
                    mediumID = Checksums.MediumChecksum(mediumID),
                    artistID = artistID,
                    replyTo = replyTo
                  )
          )
      )

    /**
      * Returns routes to query information about the albums of a medium.
      *
      * @param mediumID the ID of the medium
      * @return the route
      */
    def mediumAlbumRoutes(mediumID: String): Route =
      concat(
        pathEnd:
          get:
            handleMediumQuery(mediumID): replyTo =>
              ArchiveCommands.ReadMediumContentCommand.GetAlbums(Checksums.MediumChecksum(mediumID), replyTo),
        path(Segment / "songs"): albumID =>
          get:
            handleMediumQuery[MediaMetadata](mediumID): replyTo =>
              ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(
                mediumID = Checksums.MediumChecksum(mediumID),
                albumID = albumID,
                replyTo = replyTo
              )
      )

    /**
      * Returns routes to query information about media files stored in the
      * archive and to download them.
      *
      * @param fileID the ID of the requested media file
      * @return the route
      */
    def mediaFilesRoute(fileID: String): Route =
      concat(
        path("info"):
          get:
            val futFileInfo = contentActor.ask[ArchiveCommands.GetFileInfoResponse]: ref =>
              ArchiveCommands.ReadArchiveContentCommand.GetFileInfo(fileID, ref)
            onSuccess(futFileInfo): response =>
              response.optFileInfo match
                case Some(fileInfo) =>
                  complete(fileInfo)
                case None =>
                  complete(StatusCodes.NotFound)
      )

    pathPrefix("api"):
      pathPrefix("archive"):
        concat(
          pathPrefix("media"):
            concat(
              pathEnd:
                mediaRoute,
              pathPrefix(Segment): mediumID =>
                concat(
                  pathEnd:
                    mediumDetailsRoute(mediumID),
                  pathPrefix("artists"):
                    mediumArtistsRoutes(mediumID),
                  pathPrefix("albums"):
                    mediumAlbumRoutes(mediumID)
                )
            ),
          pathPrefix("files" / Segment): fileID =>
            mediaFilesRoute(fileID)
        )
