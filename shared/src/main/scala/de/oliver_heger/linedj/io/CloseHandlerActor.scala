/*
 * Copyright 2015-2019 The Developers Team.
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
import de.oliver_heger.linedj.io.CloseHandlerActor.{CloseComplete, ConditionSatisfied}

object CloseHandlerActor {

  /**
    * A message sent to the source actor of a close operation when all actors
    * to be closed have sent their ACK.
    */
  case object CloseComplete

  /**
    * A message processed by [[CloseHandlerActor]] telling it that an
    * additional condition the close operation depends on is now fulfilled.
    *
    * If the actor has been created with a ''conditionState'' parameter of
    * '''false''', this message must be received before the current close
    * operation can be completed.
    */
  case object ConditionSatisfied
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
  * In complex scenarios, additional conditions need to be fulfilled before a
  * close operation can be completed. This is supported by this actor in a
  * generic form: When creating an instance a flag can be passed in whether an
  * additional condition has to be taken into account. A value of '''true'''
  * means that such a condition is already fulfilled, and the actor does not
  * have to care. A value of '''false''', however, means that the current close
  * operation cannot be completed until this condition becomes satisfied; the
  * actor is notified about this by receiving a ''ConditionSatisfied'' message.
  *
  * @param source         the source actor responsible for the close operation
  * @param closeActors    the actors to be closed
  * @param conditionState state of an additional condition to be satisfied
  */
class CloseHandlerActor(source: ActorRef, closeActors: Iterable[ActorRef],
                        conditionState: Boolean) extends Actor {
  /** A set with the actors for which a close ACK is pending. */
  private var pendingCloseAck = closeActors.toSet

  /** Flag whether an additional condition is satisfied. */
  private var conditionSatisfied = conditionState

  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    closeActors foreach prepareCloseHandling
  }

  override def receive: Receive = {
    case CloseAck(actor) =>
      handleClosedActor(actor)

    case Terminated(actor) =>
      handleClosedActor(actor)

    case ConditionSatisfied =>
      conditionSatisfied = true
      completeCloseIfPossible()
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
    completeCloseIfPossible()
  }

  /**
    * Checks whether now all conditions are met to complete the close
    * operation. If so, the corresponding actions are taken.
    */
  private def completeCloseIfPossible(): Unit = {
    if (conditionSatisfied && pendingCloseAck.isEmpty) {
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
