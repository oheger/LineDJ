/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.io

import akka.actor.{Actor, ActorRef, Terminated}
import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete

object CloseHandlerActor {

  /**
    * A message sent to the source actor of a close operation when all actors
    * to be closed have sent their ACK.
    */
  case object CloseComplete

}

/**
  * An actor class supporting complex close request scenarios.
  *
  * This actor class is initialized with a source actor and a collection of
  * actors to be closed. It sends a [[CloseRequest]] to all these actors and
  * then waits for the corresponding [[CloseAck]] responses. When they have
  * arrived a ''CloseComplete'' message is sent to the source actor, and this
  * actor stops itself.
  *
  * If one of the monitored actors terminates before a ''CloseAck'' is received
  * from it, it is considered closed.
  *
  * @param source      the source actor responsible for the close operation
  * @param closeActors the actors to be closed
  */
class CloseHandlerActor(source: ActorRef, closeActors: Iterable[ActorRef]) extends Actor {
  /** A set with the actors for which a close ACK is pending. */
  private var pendingCloseAck = closeActors.toSet

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    closeActors foreach prepareCloseHandling
  }

  override def receive: Receive = {
    case CloseAck(actor) =>
      handleClosedActor(actor)

    case Terminated(actor) =>
      handleClosedActor(actor)
  }

  /**
    * Processes a message about an actor that has been closed. If now all
    * monitored actors are done, the actor sends a corresponding message and
    * stops itself.
    *
    * @param actor the actor that has been closed
    */
  private def handleClosedActor(actor: ActorRef): Unit = {
    pendingCloseAck -= actor
    if (pendingCloseAck.isEmpty) {
      source ! CloseComplete
      context stop self
    }
  }

  /**
    * Prepares an actor to be closed for the close handling. It is sent a
    * close request and added to death watch.
    *
    * @param a the actor in question
    */
  private def prepareCloseHandling(a: ActorRef): Unit = {
    a ! CloseRequest
    context watch a
  }
}
