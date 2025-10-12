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
import org.apache.pekko.actor.typed.scaladsl.Behaviors
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
  type ArchiveContentCommand = ArchiveCommands.ReadArchiveContentCommand | ArchiveCommands.UpdateArchiveContentCommand

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
  final val behavior: Factory = () => handleArchiveCommand(Nil, Map.empty)

  /**
    * The main command handler function for the archive content actor.
    *
    * @param mediaOverviews the current list of available media
    * @param media          a map with medium details for all known media
    * @return the updated behavior
    */
  private def handleArchiveCommand(mediaOverviews: List[ArchiveModel.MediumOverview],
                                   media: Map[Checksums.MediumChecksum, ArchiveModel.MediumDetails]):
  Behavior[ArchiveContentCommand] =
    Behaviors.receive:
      case (ctx, ArchiveCommands.UpdateArchiveContentCommand.AddMedium(medium)) =>
        ctx.log.info("Added medium {}.", medium.overview)
        handleArchiveCommand(medium.overview :: mediaOverviews, media + (medium.id -> medium))

      case (_, ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(_, _)) =>
        // TODO: Not yet implemented.
        Behaviors.same

      case (_, ArchiveCommands.ReadArchiveContentCommand.GetMedia(replyTo)) =>
        replyTo ! ArchiveCommands.GetMediaResponse(mediaOverviews)
        Behaviors.same

      case (_, ArchiveCommands.ReadArchiveContentCommand.GetMedium(id, replyTo)) =>
        replyTo ! ArchiveCommands.GetMediumResponse(id, media.get(id))
        Behaviors.same
