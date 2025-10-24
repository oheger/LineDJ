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
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/**
  * An object providing an actor implementation for managing the content of a
  * media archive.
  *
  * The actor receives commands during archive processing that add data about
  * media and the songs they contain. This data is kept in memory and organized
  * for quick access based on various criteria.
  */
object ArchiveContentActor:
  /**
    * Type alias for the commands supported by this actor implementation.
    */
  type ArchiveContentCommand = ArchiveCommands.ReadArchiveContentCommand |
    ArchiveCommands.UpdateArchiveContentCommand |
    ArchiveCommands.ReadMediumContentCommand

  /**
    * Type alias for a map that stores content actors for the managed media.
    */
  private type MediaContentMap = Map[Checksums.MediumChecksum, ActorRef[MediumContentActor.MediumContentCommand]]

  /**
    * A factory trait for creating new instances of the archive content actor.
    */
  trait Factory:
    /**
      * Returns a [[Behavior]] for creating a new actor instance.
      *
      * @return the [[Behavior]] for the new actor instance
      */
    def apply(): Behavior[ArchiveContentCommand]
  end Factory

  /**
    * A default [[Factory]] instance that can be used to create new actor
    * instances.
    */
  final val behavior: Factory = () => handleArchiveCommand(Nil, Map.empty, Map.empty)

  /**
    * The main command handler function for the archive content actor.
    *
    * @param mediaOverviews the current list of available media
    * @param media          a map with medium details for all known media
    * @param mediaContent   a map with actors for the content of media
    * @return the updated behavior
    */
  private def handleArchiveCommand(mediaOverviews: List[ArchiveModel.MediumOverview],
                                   media: Map[Checksums.MediumChecksum, ArchiveModel.MediumDetails],
                                   mediaContent: MediaContentMap): Behavior[ArchiveContentCommand] =
    Behaviors.receive:
      case (ctx, ArchiveCommands.UpdateArchiveContentCommand.AddMedium(medium)) =>
        ctx.log.info("Added medium {}.", medium.overview)
        val (_, nextMediaContent) = contentActorFor(ctx, mediaContent, medium.id)
        handleArchiveCommand(medium.overview :: mediaOverviews, media + (medium.id -> medium), nextMediaContent)

      case (ctx, ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(mediumID, metadata)) =>
        val (actor, nextMediaContent) = contentActorFor(ctx, mediaContent, mediumID)
        actor ! metadata
        handleArchiveCommand(mediaOverviews, media, nextMediaContent)

      case (_, ArchiveCommands.ReadArchiveContentCommand.GetMedia(replyTo)) =>
        replyTo ! ArchiveCommands.GetMediaResponse(mediaOverviews)
        Behaviors.same

      case (_, ArchiveCommands.ReadArchiveContentCommand.GetMedium(id, replyTo)) =>
        replyTo ! ArchiveCommands.GetMediumResponse(id, media.get(id))
        Behaviors.same

      case (_, req@ArchiveCommands.ReadMediumContentCommand.GetArtists(mediumID, replyTo)) =>
        handleMediumRequest(req, mediumID, replyTo, mediaContent)

      case (_, req@ArchiveCommands.ReadMediumContentCommand.GetAlbums(mediumID, replyTo)) =>
        handleMediumRequest(req, mediumID, replyTo, mediaContent)

      case (_, req@ArchiveCommands.ReadMediumContentCommand.GetSongsForArtist(mediumID, _, replyTo)) =>
        handleMediumRequest(req, mediumID, replyTo, mediaContent)

      case (_, req@ArchiveCommands.ReadMediumContentCommand.GetSongsForAlbum(mediumID, _, replyTo)) =>
        handleMediumRequest(req, mediumID, replyTo, mediaContent)

      case (_, req@ArchiveCommands.ReadMediumContentCommand.GetAlbumsForArtist(mediumID, _, replyTo)) =>
        handleMediumRequest(req, mediumID, replyTo, mediaContent)

  /**
    * Obtains the content actor for a specific medium from the given map. If it
    * does not exist, it is created now. Result is the actor and the map, which
    * may have been updated.
    *
    * @param ctx the actor context
    * @param map the map with actors
    * @param id  the ID of the affected medium
    * @return the actor and the possibly updated map
    */
  private def contentActorFor(ctx: ActorContext[ArchiveContentCommand],
                              map: MediaContentMap,
                              id: Checksums.MediumChecksum):
  (ActorRef[MediumContentActor.MediumContentCommand], MediaContentMap) =
    map.get(id) match
      case Some(actor) =>
        (actor, map)
      case None =>
        val actor = ctx.spawn(MediumContentActor(), "mediumContent_" + id.checksum)
        (actor, map + (id -> actor))

  /**
    * Handles a request for data of a specific medium in a generic way. If the
    * affected medium is known, this function passes the request to the actor
    * managing the content of this medium. Otherwise, it sends a response with
    * an empty (''None'') dataset.
    *
    * @param req          the request to be processed
    * @param mediumID     the ID of the affected medium
    * @param replyTo      the actor to send the response to
    * @param mediaContent the map with content actors for media
    * @tparam DATA the type of the requested data
    * @return the next behavior
    */
  private def handleMediumRequest[DATA](req: ArchiveCommands.ReadMediumContentCommand,
                                        mediumID: Checksums.MediumChecksum,
                                        replyTo: ActorRef[ArchiveCommands.GetMediumDataResponse[DATA]],
                                        mediaContent: MediaContentMap): Behavior[ArchiveContentCommand] =
    mediaContent.get(mediumID) match
      case Some(actor) =>
        actor ! req
      case None =>
        replyTo ! ArchiveCommands.GetMediumDataResponse(req, None)
    Behaviors.same
    