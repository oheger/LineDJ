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

package de.oliver_heger.linedj.archiveunion

import de.oliver_heger.linedj.shared.archive.metadata.MetadataProcessingEvent
import de.oliver_heger.linedj.shared.archive.union.{MediaContribution, UpdateOperationCompleted, UpdateOperationStarts}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{Behavior, Terminated}

/**
  * An actor implementation that serves as a metadata processing listener to
  * update a metadata union actor while processing local media files.
  *
  * An instance receives [[MetadataProcessingEvent]] events, converts them
  * accordingly, and passes them to a [[MetadataUnionActor]] reference. The
  * actor stops itself when the associated metadata union actor terminates.
  */
object MetadataUnionProcessingListener {
  /**
    * A trait defining a factory for creating a behavior for a new actor
    * instance.
    */
  trait Factory:
    /**
      * Returns a [[Behavior]] to create a new instance of this actor
      * implementation.
      *
      * @param metadataUnionActor a reference to the actor to forward events to
      * @return the behavior for a new actor instance
      */
    def apply(metadataUnionActor: classic.ActorRef): Behavior[MetadataProcessingEvent]

  /**
    * A default [[Factory]] instance that can be used to create behaviors for
    * new actor instances.
    */
  final val behavior: Factory = metadataUnionActor => handle(metadataUnionActor)

  /**
    * The command handler function for this actor.
    *
    * @param metadataUnionActor a reference to the actor to forward events to
    * @return the next behavior for this actor instance
    */
  private def handle(metadataUnionActor: classic.ActorRef): Behavior[MetadataProcessingEvent] = {
    Behaviors.setup { context =>
      context.watch(metadataUnionActor)

      Behaviors.receiveMessage[MetadataProcessingEvent] {
        case MetadataProcessingEvent.UpdateOperationStarts(processor) =>
          metadataUnionActor ! UpdateOperationStarts(Some(processor))
          Behaviors.same

        case MetadataProcessingEvent.UpdateOperationCompleted(processor) =>
          metadataUnionActor ! UpdateOperationCompleted(Some(processor))
          Behaviors.same

        case MetadataProcessingEvent.MediumAvailable(mediumID, _, mediaFiles) =>
          metadataUnionActor ! MediaContribution(Map(mediumID -> mediaFiles))
          Behaviors.same

        case MetadataProcessingEvent.ProcessingResultAvailable(_, result) =>
          metadataUnionActor ! result
          Behaviors.same
      }.receiveSignal {
        case (context, Terminated(_)) =>
          context.log.info(
            "Stopping MetadataUnionProcessingListener after termination of '{}'.",
            metadataUnionActor.path.name
          )
          Behaviors.stopped
      }
    }
  }
}
