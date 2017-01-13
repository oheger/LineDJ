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

/**
  * An actor class which sends a [[CloseAck]] message to a specific actor when
  * a monitored actor completes its close handling.
  *
  * This actor works together with [[CloseHandlerActor]] to implement complex
  * close handling. ''CloseHandlerActor'' waits until [[CloseAck]] messages
  * from a set of monitored actors are received and then stops itself. This
  * actor watches the handler actor and reacts on its termination; it then
  * sends a pre-defined ''CloseAck'' message to a specific target actor.
  *
  * @param handler    reference to the ''CloseHandlerActor''
  * @param closeActor the actor to be closed
  * @param target     the actor to be notified about the close operation
  */
class CloseNotifyActor(handler: ActorRef, closeActor: ActorRef, target: ActorRef) extends Actor {
  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    context watch handler
  }

  override def receive: Receive = {
    case Terminated(_) =>
      target ! CloseAck(closeActor)
      context stop self
  }
}
