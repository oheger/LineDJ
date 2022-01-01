/*
 * Copyright 2015-2022 The Developers Team.
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
import scalaz.State
import scalaz.State._

/**
  * A class representing the processing state while reading the content of an
  * HTTP archive.
  *
  * @param mediaInProgress  the number of media that are currently processed
  * @param ack              an actor that waits for an ACK message
  * @param propagateMsg     a message to send to the propagation actor
  * @param contentInArchive flag whether content
  * @param removeTriggered  flag whether a remove request has been sent
  * @param pendingResult    a result that is pending to be sent; it cannot be
  *                         sent until a propagation operation was confirmed
  * @param pendingClient    a client that sent the last pending result
  * @param seqNo            the sequence number of the current scan operation
  * @param scanInProgress   flag whether a scan operation is currently ongoing
  */
case class ContentProcessingState(mediaInProgress: Int,
                                  ack: Option[ActorRef],
                                  propagateMsg: Option[PropagateMediumResult],
                                  contentInArchive: Boolean,
                                  removeTriggered: Boolean,
                                  pendingResult: Option[MediumProcessingResult],
                                  pendingClient: Option[ActorRef],
                                  seqNo: Int,
                                  scanInProgress: Boolean)

/**
  * A class storing information about actions to be executed when there is a
  * transition of the content processing state.
  *
  * When the processing state changes, typically some actions need to be
  * performed, such as propagating a new result or sending an ACK message.
  *
  * @param propagateMsg an optional result message to be propagated
  * @param actorToAck   an actor to send an ACK message to
  */
case class ProcessingStateTransitionData(propagateMsg: Option[PropagateMediumResult],
                                         actorToAck: Option[ActorRef])

/**
  * A service responsible for managing the processing state when reading the
  * content from an HTTP archive.
  *
  * The content of an HTTP archive is loaded and processed using a stream that
  * yields [[MediumProcessingResult]] objects. Whenever such a result becomes
  * available the data it contains has to be sent to the union media archive.
  * This service controls these send operations by creating the corresponding
  * propagation messages. It also takes care that only a configurable number of
  * media results are processed at a given point in time (to avoid uncontrolled
  * memory usage). In addition, it has to be ensured that content of the HTTP
  * archive is removed from the union archive before another scan operation
  * starts.
  */
trait ContentProcessingUpdateService {
  /** The type for updates of the internal state. */
  type StateUpdate[A] = State[ContentProcessingState, A]

  /**
    * Updates the state for a newly started scan operation. Note that this
    * function is limited to basic state changes. Starting of a scan requires
    * some complex actions (like setting up the processing stream) that are not
    * handled by this service. If an operation is already in progress, no state
    * changes are performed, and a value of '''false''' is returned.
    *
    * @return the updated state and a flag whether an operation was started
    */
  def processingStarts(): StateUpdate[Boolean]

  /**
    * Updates the state for a new result becoming available. A corresponding
    * message to the propagation actor is created. If possible, this message
    * can be sent directly; otherwise, it has to be parked until results have
    * been propagated.
    *
    * @param result        the new result
    * @param client        the client actor
    * @param maxInProgress the maximum number of results in progress
    * @return the updated state
    */
  def resultAvailable(result: MediumProcessingResult, client: ActorRef, maxInProgress: Int):
  StateUpdate[Unit]

  /**
    * Updates the state when a confirmation arrives that a result has been
    * propagated to the union archive. This decrements the counter for the
    * results in progress. If there is a result pending for propagation, it
    * can be handled now.
    *
    * @param seqNo         the sequence number from the confirmation message
    * @param maxInProgress the maximum number of results in progress
    * @return the updated state
    */
  def resultPropagated(seqNo: Int, maxInProgress: Int): StateUpdate[Unit]

  /**
    * Updates the state when processing of the HTTP archive is complete.
    *
    * @return the updated state
    */
  def processingDone(): StateUpdate[Unit]

  /**
    * Updates the state to reflect that transition actions are executed and
    * returns an object with information about these actions. It is assumed
    * that the caller directly executes the actions described by the returned
    * object.
    *
    * @return the updated state and transition data
    */
  def fetchTransitionData(): StateUpdate[ProcessingStateTransitionData]

  /**
    * Handles a newly available result by updating the state accordingly and
    * returning information about transition actions.
    *
    * @param result        the new result
    * @param client        the client actor
    * @param maxInProgress the maximum number of results in progress
    * @return the updated state and transition data
    */
  def handleResultAvailable(result: MediumProcessingResult, client: ActorRef, maxInProgress: Int):
  StateUpdate[ProcessingStateTransitionData] = for {
    _ <- resultAvailable(result, client, maxInProgress)
    data <- fetchTransitionData()
  } yield data

  /**
    * Handles a result propagated confirmation by updating the state
    * accordingly and returning information about transition actions.
    *
    * @param seqNo         the sequence number from the confirmation message
    * @param maxInProgress the maximum number of results in progress
    * @return the updated state and transition data
    */
  def handleResultPropagated(seqNo: Int, maxInProgress: Int):
  StateUpdate[ProcessingStateTransitionData] = for {
    _ <- resultPropagated(seqNo, maxInProgress)
    data <- fetchTransitionData()
  } yield data
}

object ContentProcessingUpdateServiceImpl extends ContentProcessingUpdateService {
  /** Constant for the initial content processing state. */
  val InitialState: ContentProcessingState = ContentProcessingState(mediaInProgress = 0,
    ack = None, propagateMsg = None, pendingResult = None, pendingClient = None,
    contentInArchive = false, removeTriggered = true, seqNo = 0, scanInProgress = false)

  override def processingStarts(): StateUpdate[Boolean] = State { s =>
    if (s.scanInProgress) (s, false)
    else (s.copy(mediaInProgress = 0, ack = None, propagateMsg = None, pendingClient = None,
      pendingResult = None, seqNo = s.seqNo + 1, removeTriggered = false,
      scanInProgress = true), true)
  }

  override def resultAvailable(result: MediumProcessingResult, client: ActorRef,
                               maxInProgress: Int): StateUpdate[Unit] = modify { s =>
    if (s.propagateMsg.isDefined || s.pendingResult.isDefined || result.seqNo != s.seqNo) s
    else if (s.mediaInProgress >= maxInProgress)
      s.copy(pendingClient = Some(client), pendingResult = Some(result))
    else s.copy(mediaInProgress = s.mediaInProgress + 1, ack = Some(client),
      propagateMsg = Some(PropagateMediumResult(result, removeContent = shouldTriggerRemove(s))),
      contentInArchive = true, removeTriggered = true)
  }

  override def resultPropagated(seqNo: Int, maxInProgress: Int): StateUpdate[Unit] = modify { s =>
    if (seqNo == s.seqNo) {
      val nextInProgress = math.max(0, s.mediaInProgress - 1)
      s.pendingResult match {
        case Some(result) if nextInProgress < maxInProgress =>
          s.copy(ack = s.pendingClient, pendingClient = None,
            propagateMsg = Some(PropagateMediumResult(result, removeContent = false)),
            pendingResult = None)
        case _ =>
          s.copy(mediaInProgress = nextInProgress)
      }
    }
    else s
  }

  override def processingDone(): StateUpdate[Unit] = modify { s =>
    s.copy(scanInProgress = false)
  }

  override def fetchTransitionData(): StateUpdate[ProcessingStateTransitionData] = State { s =>
    (s.copy(ack = None, propagateMsg = None),
      ProcessingStateTransitionData(s.propagateMsg, s.ack))
  }

  /**
    * Returns a flag whether for an incoming result a remove operation from the
    * union archive needs to be triggered. The result depends on the current
    * state: whether information is stored in the union archive and whether
    * this is the first result of a scan operation.
    *
    * @param s the state
    * @return a flag whether a remove operation should be triggered
    */
  private def shouldTriggerRemove(s: ContentProcessingState): Boolean =
    s.contentInArchive && !s.removeTriggered
}