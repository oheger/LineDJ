/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl

import akka.actor.{Actor, ActorRef}
import de.oliver_heger.linedj.shared.archive.union.RemovedArchiveComponentProcessed

/**
  * An actor that is responsible for propagating media data fetched from an
  * HTTP archive to the union archive.
  *
  * This actor is used by the sink of the stream that loads all information
  * from an HTTP archive. As soon as the information for a medium is available,
  * it is passed to this actor. The actor then has to send all messages
  * required to propagate this medium to the union archive.
  *
  * When another scan starts it may be necessary to remove the old content of
  * the associated HTTP archive from the union archive first. This is
  * controlled via a flag in messages sent to this actor. In this case, the
  * actor sends the corresponding request to the union archive and blocks
  * further result propagation until the confirmation arrives.
  *
  * For each medium that has been propagated to the union archive an ACK
  * message is sent. That way back-pressure is generated, so that the stream
  * reading data from the HTTP archive can slow down if necessary.
  *
  * @param propagationService the content propagation update service
  * @param mediaManager       the union media manager actor
  * @param metaDataManager    the union meta data manager actor
  * @param archiveID          the ID of the HTTP archive (this is used as
  *                           component ID in messages for the union archive)
  */
class ContentPropagationActor(private[impl] val propagationService: ContentPropagationUpdateService,
                              mediaManager: ActorRef, metaDataManager: ActorRef,
                              archiveID: String) extends Actor {
  /**
    * Creates a new instance of ''ContentPropagationActor'' with the specified
    * parameters. The default content propagation service is used.
    *
    * @param mediaManager    the union media manager actor
    * @param metaDataManager the union meta data manager actor
    * @param archiveUri      the URI of the HTTP archive
    * @return the newly created instance
    */
  def this(mediaManager: ActorRef, metaDataManager: ActorRef,
           archiveUri: String) = this(ContentPropagationUpdateServiceImpl, mediaManager,
    metaDataManager, archiveUri)

  /** The propagation state managed by this actor. */
  private var state = ContentPropagationUpdateServiceImpl.InitialState

  override def receive: Receive = {
    case PropagateMediumResult(result, remove) =>
      updateStateAndSendMessages(propagationService.handleMediumProcessed(result, createActors(),
        archiveID, remove))

    case RemovedArchiveComponentProcessed(_) =>
      updateStateAndSendMessages(propagationService.handleRemovalConfirmed())
  }

  /**
    * Updates the current propagation state accorind to the specified update
    * object and sends the resulting messages to the target actors.
    *
    * @param update the object describing the state update
    */
  private def updateStateAndSendMessages(update: ContentPropagationUpdateServiceImpl
  .StateUpdate[Iterable[MessageData]]): Unit = {
    val (next, sendMsg) = update(state)
    state = next
    sendMsg foreach sendMessages
  }

  /**
    * Handles the specified ''MessageData'' object by sending the messages to
    * the target actor.
    *
    * @param data the ''MessageData''
    */
  private def sendMessages(data: MessageData): Unit = {
    data.messages foreach data.target.!
  }

  /**
    * Creates an object with the actors involved in a propagation operation.
    *
    * @return the propagation actors
    */
  private def createActors(): PropagationActors =
    PropagationActors(mediaManager, metaDataManager, sender())
}
