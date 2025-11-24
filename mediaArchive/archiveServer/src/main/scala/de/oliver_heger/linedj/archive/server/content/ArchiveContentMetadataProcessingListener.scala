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
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetadata, MetadataProcessingEvent}
import de.oliver_heger.linedj.shared.archive.union.{MetadataProcessingError, MetadataProcessingSuccess}
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
    * A factory trait for creating new instances of the archive content
    * metadata processing listener actor.
    */
  trait Factory:
    /**
      * Returns a [[Behavior]] for creating a new actor instance.
      *
      * @param contentActor the reference to the content actor to interact with
      * @return the [[Behavior]] of the new actor instance
      */
    def apply(contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand]): Behavior[MetadataProcessingEvent]
  end Factory

  /**
    * A default [[Factory]] instance that can be used to create new actor
    * instances.
    */
  final val behavior: Factory = contentActor =>
    handleEvent(contentActor, Map.empty, Map.empty)

  /**
    * The main message handling function of this actor.
    *
    * @param contentActor          reference to the archive content actor
    * @param pendingAvailableMedia a map with pending available media events
    * @param pendingDescriptions   a map with pending media description events
    * @return the updated behavior
    */
  private def handleEvent(contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
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

    case (ctx, e: MetadataProcessingEvent.ProcessingResultAvailable) =>
      e.result match
        case suc: MetadataProcessingSuccess =>
          contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMediaFile(
            e.checksum,
            suc.uri,
            fetchMetadata(suc)
          )
        case MetadataProcessingError(_, uri, exception) =>
          ctx.log.warn("Received failed processing format for '{}'.", uri, exception)
      Behaviors.same

    case (ctx, MetadataProcessingEvent.ProcessingCompleted(_)) =>
      ctx.log.info("Received processing completed event. Listener actor terminates.")
      Behaviors.stopped

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
                            contentActor: ActorRef[ArchiveCommands.UpdateArchiveContentCommand],
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
    contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMedium(mediumDetails)

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

  /**
    * Obtains metadata from the given processing result and tries to populate
    * missing properties with standard information.
    *
    * @param result the processing result
    * @return the possibly augmented metadata from the result
    */
  private def fetchMetadata(result: MetadataProcessingSuccess): MediaMetadata =
    if result.metadata.title.isDefined then
      result.metadata
    else
      result.metadata.copy(title = Some(result.uri.name))
