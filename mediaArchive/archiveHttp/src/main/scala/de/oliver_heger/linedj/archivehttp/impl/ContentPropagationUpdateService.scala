/*
 * Copyright 2015-2018 The Developers Team.
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

import akka.actor.ActorRef
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.union.{AddMedia, ArchiveComponentRemoved, MediaContribution}
import scalaz.State
import scalaz.State._

/**
  * Data class that represents a sequence of messages to an actor to be sent.
  *
  * With instances of this class the update service indicates the messages to
  * be sent when a state transition occurs.
  *
  * @param target   the target to receive the messages
  * @param messages the actual messages
  */
case class MessageData(target: ActorRef, messages: Iterable[Any])

/**
  * Data class collecting the actors involved in a result propagation
  * operation.
  *
  * These actor references are required by the propagation update service to
  * generate the messages to be sent.
  *
  * @param mediaManager the union media manager actor
  * @param metaManager  the union meta data manager actor
  * @param client       the client actor to ACK when results are processed
  */
case class PropagationActors(mediaManager: ActorRef, metaManager: ActorRef, client: ActorRef)

/**
  * A class representing the propagation state to the union archive.
  *
  * The state contains the messages that are to be sent to the actors of the
  * union archive. If the ACK of a remove request is pending, messages cannot
  * be sent directly, but have to be buffered.
  *
  * @param messages        a list with messages to be sent
  * @param pendingMessages messages that are pending to be sent
  * @param removeAck       flag whether a remove ACK has been received
  */
case class ContentPropagationState(messages: List[MessageData],
                                   pendingMessages: List[MessageData],
                                   removeAck: Boolean)

/**
  * A service that manages the state of the propagation of media data to the
  * union archive during content processing of an HTTP archive.
  *
  * While reading the content document and the referenced documents of an HTTP
  * archive, the data extracted must be eventually passed to the union archive.
  * This is not too difficult for the media data, but becomes more complex when
  * another scan starts and the content of the current HTTP archive has to be
  * removed first from the union archive.
  *
  * This service takes care about this by providing corresponding update
  * functions for a propagation state object.
  */
trait ContentPropagationUpdateService {
  /** Type for a state update. */
  type StateUpdate[T] = State[ContentPropagationState, T]

  /**
    * Updates the state for another medium that has been processed. Messages to
    * be sent for this medium to the union archive actors are generated. If the
    * boolean remove parameter is '''true''', a request to remove the content
    * of the current HTTP archive from the union archive is generated as well.
    *
    * @param result     the medium processing result
    * @param actors     involved actors
    * @param archiveUri URI for the current HTTP archive
    * @param remove     flag whether archive content is to be removed
    * @return the updated state
    */
  def mediumProcessed(result: MediumProcessingResult, actors: PropagationActors,
                      archiveUri: String, remove: Boolean): StateUpdate[Unit]

  /**
    * Updates the state when an ACK for a remove request arrives. Then pending
    * messages with results can be actually sent.
    *
    * @return the updated state
    */
  def removalConfirmed(): StateUpdate[Unit]

  /**
    * Updates the state regarding messages to be sent. With this function the
    * messages that are ready to be sent can be fetched. The state is updated
    * as if the messages were already sent to their receivers.
    *
    * @return the updated state and the messages to be sent
    */
  def messagesToSend(): StateUpdate[Iterable[MessageData]]

  /**
    * Handles a notification about a processed medium by updating the state
    * accordingly and returning a sequence with messages that have to be sent
    * now.
    *
    * @param result     the medium processing result
    * @param actors     involved actors
    * @param archiveUri URI for the current HTTP archive
    * @param remove     flag whether archive content is to be removed
    * @return the updated state and messages to be sent
    */
  def handleMediumProcessed(result: MediumProcessingResult, actors: PropagationActors,
                            archiveUri: String, remove: Boolean):
  StateUpdate[Iterable[MessageData]] = for {
    _ <- mediumProcessed(result, actors, archiveUri, remove)
    msg <- messagesToSend()
  } yield msg

  /**
    * Handles an incoming confirmation of a removal request by updating the
    * state accordingly and returning a sequence of messages that have to be
    * sent now.
    *
    * @return the updated state and messages to be sent
    */
  def handleRemovalConfirmed(): StateUpdate[Iterable[MessageData]] = for {
    _ <- removalConfirmed()
    msg <- messagesToSend()
  } yield msg
}

/**
  * The implementation of the ''ContentPropagationUpdateService''.
  */
object ContentPropagationUpdateServiceImpl extends ContentPropagationUpdateService {
  /** Constant for the initial propagation state. */
  val InitialState: ContentPropagationState =
    ContentPropagationState(messages = Nil, pendingMessages = Nil, removeAck = true)

  override def mediumProcessed(result: MediumProcessingResult, actors: PropagationActors,
                               archiveUri: String, remove: Boolean):
  StateUpdate[Unit] = modify { s =>
    if (remove)
      ContentPropagationState(messages = List(MessageData(actors.mediaManager,
        Seq(ArchiveComponentRemoved(archiveUri)))), removeAck = false,
        pendingMessages = createMessagesForMedium(result, actors, archiveUri, result.seqNo))
    else {
      val orgMessages = if (s.removeAck) s.messages else s.pendingMessages
      val updateMessages = createMessagesForMedium(result, actors, archiveUri,
        result.seqNo) ::: orgMessages
      val (newMessages, newPending) =
        if (s.removeAck) (updateMessages, s.pendingMessages)
        else (s.messages, updateMessages)
      s.copy(messages = newMessages, pendingMessages = newPending)
    }
  }

  override def removalConfirmed(): StateUpdate[Unit] = modify { s =>
    if (s.removeAck) s
    else ContentPropagationState(messages = s.pendingMessages, pendingMessages = Nil,
      removeAck = true)
  }

  override def messagesToSend(): StateUpdate[Iterable[MessageData]] = State { s =>
    (s.copy(messages = Nil), s.messages)
  }

  /**
    * Creates a list with ''MessageData'' objects representing the messages to
    * be sent for the result of a processed medium.
    *
    * @param result     the ''MediumProcessingResult''
    * @param actors     actors involved in propagation
    * @param archiveUri the URI of the archive
    * @param seqNo      the sequence number of the current operation
    * @return a list with the messages to propagate this result
    */
  private def createMessagesForMedium(result: MediumProcessingResult, actors: PropagationActors,
                                      archiveUri: String, seqNo: Int): List[MessageData] =
    List(createAddMediaMessage(result, actors, archiveUri),
      createMetaDataMessages(result, actors), createPropagatedMessage(seqNo, actors))

  /**
    * Creates the message to the media manager actor that adds a medium
    * description to the union archive.
    *
    * @param result     the current result object
    * @param actors     actors involved in propagation
    * @param archiveUri the URI of the archive
    * @return the ''MessageData'' object with the message
    */
  private def createAddMediaMessage(result: MediumProcessingResult, actors: PropagationActors,
                                    archiveUri: String): MessageData =
    MessageData(actors.mediaManager,
      Seq(AddMedia(Map(result.mediumInfo.mediumID -> result.mediumInfo), archiveUri,
        Some(actors.client))))

  /**
    * Creates a ''MessageData'' with the messages to be sent to the meta data
    * manager to add the content of a medium to the union archive.
    *
    * @param result the result object for the medium
    * @param actors actors involved in propagation
    * @return the ''MessageData'' object
    */
  private def createMetaDataMessages(result: MediumProcessingResult, actors: PropagationActors):
  MessageData = {
    val files = result.metaData map (m => FileData(m.path, m.metaData.size))
    val contribution = MediaContribution(Map(result.mediumInfo.mediumID -> files))
    val messages = contribution :: result.metaData.toList
    MessageData(actors.metaManager, messages)
  }

  /**
    * Creates a ''MessageData'' object with an ACK message indicating that the
    * results of a medium have been propagated to the union archive.
    *
    * @param seqNo  the sequence number of the current operation
    * @param actors actors involved in propagation
    * @return the ''MessageData'' object
    */
  private def createPropagatedMessage(seqNo: Int, actors: PropagationActors): MessageData =
    MessageData(actors.client, Seq(MediumPropagated(seqNo)))
}
