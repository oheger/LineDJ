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

import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor.ArchiveContentCommand
import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor.ArchiveContentCommand.AddMedium
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MetadataProcessingEvent
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.util.{Failure, Try}

/**
  * An object providing an actor implementation to process 
  * [[MetadataProcessingEvent]] events.
  *
  * This actor is used to populate an [[ArchiveContentActor]] with the content
  * of a local media archive while it is scanned for media.
  */
object ArchiveContentMetadataProcessingListener:
  /**
    * Returns a [[Behavior]] for creating a new actor instance.
    *
    * @param contentActor the actor that manages the archive content
    * @return the [[Behavior]] for the new actor instance
    */
  def behavior(contentActor: ActorRef[ArchiveContentCommand]): Behavior[MetadataProcessingEvent] =
    handleEvent(contentActor, Map.empty, Map.empty)

  /**
    * The main message handling function of this actor.
    *
    * @param contentActor          reference to the archive content actor
    * @param pendingAvailableMedia a map with pending available media events
    * @param pendingDescriptions   a map with pending media description events
    * @return the updated behavior
    */
  private def handleEvent(contentActor: ActorRef[ArchiveContentCommand],
                          pendingAvailableMedia: Map[MediumID, MetadataProcessingEvent.MediumAvailable],
                          pendingDescriptions: Map[MediumID, MetadataProcessingEvent.MediumDescriptionAvailable]):
  Behavior[MetadataProcessingEvent] = Behaviors.receive:
    case (ctx, e: MetadataProcessingEvent.MediumAvailable) =>
      pendingDescriptions.get(e.mediumID) match
        case Some(description) =>
          forwardMedium(ctx, contentActor, e, description)
          handleEvent(contentActor, pendingAvailableMedia, pendingDescriptions - e.mediumID)
        case None =>
          handleEvent(contentActor, pendingAvailableMedia + (e.mediumID -> e), pendingDescriptions)

    case (ctx, e: MetadataProcessingEvent.MediumDescriptionAvailable) =>
      pendingAvailableMedia.get(e.mediumID) match
        case Some(medium) =>
          forwardMedium(ctx, contentActor, medium, e)
          handleEvent(contentActor, pendingAvailableMedia - e.mediumID, pendingDescriptions)
        case None =>
          handleEvent(contentActor, pendingAvailableMedia, pendingDescriptions + (e.mediumID -> e))

    case _ => Behaviors.same

  /**
    * Creates a [[ArchiveModel.MediumDetails]] object from the passed in
    * parameters and forwards it to the content actor.
    *
    * @param ctx               the actor context
    * @param contentActor      the content actor
    * @param mediumAvailable   the medium available event
    * @param mediumDescription the medium description event
    */
  private def forwardMedium(ctx: ActorContext[MetadataProcessingEvent],
                            contentActor: ActorRef[ArchiveContentCommand],
                            mediumAvailable: MetadataProcessingEvent.MediumAvailable,
                            mediumDescription: MetadataProcessingEvent.MediumDescriptionAvailable): Unit =
    val mediumDetails = ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(
        id = mediumAvailable.checksum,
        title = mediumDescription.mediumDescription.name
      ),
      description = mediumDescription.mediumDescription.description,
      orderMode = toOrderMode(ctx, mediumDescription.mediumDescription.orderMode)
    )

    ctx.log.info("Got medium '{}'.", mediumDetails.overview.title)
    contentActor ! AddMedium(mediumDetails)

  /**
    * Converts a string for the order mode to the corresponding enumeration
    * literal. If this fails, no order mode is used, and an error is logged.
    *
    * @param ctx      the actor context
    * @param orderStr the order mode as string
    * @return an [[Option]] with the converted order mode
    */
  private def toOrderMode(ctx: ActorContext[MetadataProcessingEvent],
                          orderStr: String): Option[ArchiveModel.OrderMode] =
    Try {
      ArchiveModel.OrderMode.valueOf(orderStr)
    }.recoverWith { ex =>
      ctx.log.error("Unsupported order mode '{}'.", orderStr)
      Failure(ex)
    }.toOption