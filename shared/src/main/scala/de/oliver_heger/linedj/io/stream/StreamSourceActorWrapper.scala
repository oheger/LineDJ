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

package de.oliver_heger.linedj.io.stream

import akka.actor.{Actor, ActorRef, Terminated}

/**
  * An actor class intended to wrap data actors for an [[ActorSource]].
  *
  * This actor class can be used to adapt another actor to adhere to the
  * protocol required by an ''ActorSource'', especially regarding error
  * handling. Some data actors terminate themselves when an error occurs; this
  * error handling is inappropriate for an ''ActorSource'' which expects a
  * special error message. Therefore, this actor monitors another actor and
  * sends a corresponding error message when it detects that the wrapped actor
  * died.
  *
  * It also stops the wrapped actor when itself is stopped.
  *
  * @param wrappedActor the actor to be wrapped
  * @param termErrorMsg message to be sent when the wrapped actor dies
  */
class StreamSourceActorWrapper(wrappedActor: ActorRef, termErrorMsg: Any) extends Actor {
  /** Holds the client actor of an ongoing request. */
  private var optClient: Option[ActorRef] = None

  /** A flag whether the wrapped actor has been terminated. */
  private var wrappedActorTerminated = false

  override def preStart(): Unit = {
    super.preStart()
    context watch wrappedActor
  }

  /**
    * @inheritdoc This implementation stops the wrapped actor if it is still
    *             alive.
    */
  override def postStop(): Unit = {
    if (!wrappedActorTerminated) {
      context stop wrappedActor
    }
    super.postStop()
  }

  override def receive: Receive = {
    case Terminated(_) =>
      wrappedActorTerminated = true
      sendAnswer(termErrorMsg)

    case m if sender() == wrappedActor =>
      sendAnswer(m)

    case m if sender() != wrappedActor && optClient.isEmpty =>
      if (wrappedActorTerminated) {
        sender() ! termErrorMsg
      } else {
        optClient = Some(sender())
        wrappedActor ! m
      }
  }

  /**
    * Sends an answer for a pending request. If currently no request is in
    * progress, the message is ignored.
    *
    * @param m the message to be sent to the waiting client
    */
  private def sendAnswer(m: Any): Unit = {
    optClient foreach (_ ! m)
    optClient = None
  }
}
