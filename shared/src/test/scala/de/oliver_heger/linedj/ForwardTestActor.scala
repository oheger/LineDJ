/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import de.oliver_heger.linedj.ForwardTestActor.ForwardedMessage

object ForwardTestActor {

  /**
    * A message sent by [[ForwardTestActor]] to the sender to prove whether
    * forwarding worked as expected. In a test case, it has to be checked
    * whether the sending actor receives a message of this type with the
    * message to be forwarded as payload.
    *
    * @param originalMsg the original message that has been forwarded
    */
  case class ForwardedMessage(originalMsg: Any)

  /**
    * Convenience method for creating a new instance of the forward test
    * actor.
    *
    * @param system reference to the actor system
    * @return a reference to the newly created actor
    */
  def apply()(implicit system: ActorSystem): ActorRef =
  system.actorOf(Props[ForwardTestActor])
}

/**
  * A test actor implementation which can be used for testing forwarding of
  * messages to other actors.
  *
  * An instance of this actor can be injected into another actor as target of
  * a forwarding operation. What it does is just wrapping the received
  * message into a ''ForwardedMessage'' object and sending this object to the
  * sender. If forwarding has been implemented correctly, the original sender
  * actor should receive this message.
  */
class ForwardTestActor extends Actor {
  override def receive: Receive = {
    case msg =>
      sender ! ForwardedMessage(msg)
  }
}
