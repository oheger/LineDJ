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

package de.oliver_heger.linedj.io

import de.oliver_heger.linedj.io.CloseHandlerActor.CloseComplete
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.{actor => classic}

/**
  * A module offering functionality related to the handling of actors
  * supporting the close protocol.
  *
  * The functionality provided here is similar to what [[CloseSupport]] has for
  * classic actors. Basically, there is a function to trigger and control the
  * closing of a number of dependent actors. The close operation happens
  * asynchronously, and when it is done, the triggering typed actor is sent a
  * configurable message.
  */
object CloseSupportTyped:
  /**
    * Triggers a close operation on the given dependencies. This function sends
    * a close request to each actor in the dependencies and waits for their
    * responses. Only after all Ack messages have arrived (or the actors have
    * died), the provided message is sent to the client actor, indicating the
    * completion of the operation.
    *
    * @param clientContext the context of the client actor
    * @param client        the actor to notify when the operation is complete
    * @param ackMessage    the message to sent to the client actor
    * @param dependencies  the actors to close
    * @tparam M the type of notify message
    */
  def triggerClose[M](clientContext: ActorContext[M],
                      client: ActorRef[M],
                      ackMessage: M,
                      dependencies: Iterable[classic.ActorRef]): Unit =
    clientContext.actorOf(
      classic.Props(new Actor with ChildActorFactory with CloseSupport with classic.ActorLogging {
        override def preStart(): Unit = {
          onCloseRequest(self, dependencies, self, this)
          log.info("Triggering typed close request for {}.", client.path)
        }

        override def receive: Receive = {
          case CloseComplete =>
            log.info("CloseComplete received for {}.", client.path)
            client ! ackMessage
            context stop self
        }
      }))
