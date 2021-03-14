/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archive.group

import akka.actor.ActorRef
import scalaz.State

/**
  * A data class representing the scan state of a group of media archives.
  *
  * The purpose of this class is to collect all the information required to
  * make sure that scan operations for all archives in the group can be
  * started, but only a single archive executes a scan operation at a given
  * time.
  *
  * @param pendingScanRequests a set with the actors for which a scan operation
  *                            needs to be triggered
  * @param scanInProgress      a flag whether a scan operation is currently in
  *                            progress
  * @param currentScanRequest  an ''Option'' holding the actor to which a scan
  *                            request needs to be sent
  */
private case class GroupScanState(pendingScanRequests: Set[ActorRef],
                                  scanInProgress: Boolean,
                                  currentScanRequest: Option[ActorRef])

/**
  * A trait defining the protocol of the archive group scan service.
  *
  * The service is used to handle scan requests for media archives that belong
  * to a group. It has to make sure that requests are handled correctly, but at
  * a given time only a single scan operation is active in the group. To
  * achieve this, the service gets notifications about incoming scan requests
  * and when a scan operation is completed. It records pending requests and
  * decides, which actors need to be sent a scan request.
  */
private trait GroupScanStateService {
  /**
    * Type definition for a state update. The managed state type is
    * [[GroupScanState]]; each update can yield an additional result.
    */
  type StateUpdate[A] = State[GroupScanState, A]

  /**
    * Updates the state for an incoming scan request for an actor in the group.
    *
    * @param archiveActor the actor that received the request
    * @return the updated state
    */
  def scanRequested(archiveActor: ActorRef): StateUpdate[Unit]

  /**
    * Updates the state for a completed scan operation. If there are pending
    * requests, another actor in the group can be sent such a request.
    *
    * @return the updated state
    */
  def scanCompleted(): StateUpdate[Unit]

  /**
    * Checks whether the current state requires a scan request to be sent to an
    * actor. The state is then updated indicating that the request has been
    * processed.
    *
    * @return the updated state and an ''Option'' with an actor to send a scan
    *         request
    */
  def fetchNextScanRequest(): StateUpdate[Option[ActorRef]]

  /**
    * Updates the state for an incoming scan request for an actor in the group
    * and returns an ''Option'' with an actor to send a scan request to.
    *
    * @param archiveActor the actor that received the request
    * @return the updated state and an ''Option'' with an actor to send a scan
    *         request
    */
  def handleScanRequest(archiveActor: ActorRef): StateUpdate[Option[ActorRef]] = for {
    _ <- scanRequested(archiveActor)
    nextRequest <- fetchNextScanRequest()
  } yield nextRequest

  /**
    * Updates the state for a completed scan operation and returns an
    * ''Option'' with an actor to send another scan request to. If there are
    * pending requests available, the option is defined; otherwise, the state
    * is reset, so that no scan is currently in progress.
    *
    * @return the updated state and an ''Option'' with an actor to send a scan
    *         request
    */
  def handleScanCompleted(): StateUpdate[Option[ActorRef]] = for {
    _ <- scanCompleted()
    nextRequest <- fetchNextScanRequest()
  } yield nextRequest
}

/**
  * The default implementation of the ''GroupScanStateService'' trait.
  */
private object GroupScanStateServiceImpl extends GroupScanStateService {
  /** Constant for an initial state object. */
  val InitialState: GroupScanState = GroupScanState(scanInProgress = false, pendingScanRequests = Set.empty,
    currentScanRequest = None)

  /**
    * Updates the state for an incoming scan request for an actor in the group.
    *
    * @param archiveActor the actor that received the request
    * @return the updated state
    */
  override def scanRequested(archiveActor: ActorRef): StateUpdate[Unit] = State { s =>
    (if (s.scanInProgress) s.copy(pendingScanRequests = s.pendingScanRequests + archiveActor)
    else s.copy(scanInProgress = true, currentScanRequest = Some(archiveActor)), ())
  }

  /**
    * Updates the state for a completed scan operation. If there are pending
    * requests, another actor in the group can be sent such a request.
    *
    * @return the updated state
    */
  override def scanCompleted(): StateUpdate[Unit] = State { s =>
    (if (s.pendingScanRequests.isEmpty)
      s.copy(scanInProgress = false)
    else {
      val nextRequest = s.pendingScanRequests.head
      GroupScanState(scanInProgress = true, pendingScanRequests = s.pendingScanRequests.tail,
        currentScanRequest = Some(nextRequest))
    }, ())
  }

  /**
    * Checks whether the current state requires a scan request to be sent to an
    * actor. The state is then updated indicating that the request has been
    * processed.
    *
    * @return the updated state and an ''Option'' with an actor to send a scan
    *         request
    */
  override def fetchNextScanRequest(): StateUpdate[Option[ActorRef]] = State { s =>
    (s.copy(currentScanRequest = None), s.currentScanRequest)
  }
}
