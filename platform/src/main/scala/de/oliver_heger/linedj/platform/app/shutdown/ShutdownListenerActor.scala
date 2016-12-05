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

package de.oliver_heger.linedj.platform.app.shutdown

import akka.actor.{Actor, ActorLogging, ActorRef}
import de.oliver_heger.linedj.platform.app.ShutdownListener
import de.oliver_heger.linedj.platform.app.shutdown.ShutdownListenerActor.{AddShutdownListener,
ProcessData, RemoveShutdownListener}
import de.oliver_heger.linedj.platform.comm.MessageBus

object ShutdownListenerActor {

  /**
    * A message processed by [[ShutdownListenerActor]] telling it that a new
    * ''ShutdownListener'' is now available. The new listener is added to the
    * internal list of available listeners.
    *
    * @param listener the new ''ShutdownListener''
    */
  case class AddShutdownListener(listener: ShutdownListener)

  /**
    * A message processed by [[ShutdownListenerActor]] telling it that a
    * ''ShutdownListener'' is no longer available. The listener is then
    * removed from the internal list of available listeners.
    *
    * @param listener the ''ShutdownListener'' to be removed
    */
  case class RemoveShutdownListener(listener: ShutdownListener)

  /**
    * A message sent on the message bus to trigger an iteration over the
    * services currently available. This message is processed by a temporary
    * listener on the message bus, so that services are guaranteed to be
    * invoked on the event dispatch thread.
    *
    * @param process        the original ''Process'' message
    * @param services       a collection with the services to be processed
    * @param registrationID the registration ID for the message bus listener
    */
  private case class ProcessData(process: ShutdownManagementActor.Process, services:
  Iterable[ShutdownListener],
                                 registrationID: Int)

}

/**
  * An actor class responsible for the management of the shutdown listeners
  * registered  in the system.
  *
  * This actor class offers messages for adding or removing shutdown listeners.
  * On receiving of a ''Process'' message, it iterates over all listeners
  * notifying them about the shutdown operation. Iteration stops if a listener
  * indicates that the operation is to be aborted.
  *
  * After all listeners have been notified successfully, the actual shutdown
  * is triggered by sending a corresponding message to the
  * [[ShutdownManagementActor]].
  *
  * @param messageBus    the UI message bus
  * @param shutdownActor the ''ShutdownManagementActor''
  */
class ShutdownListenerActor(messageBus: MessageBus, shutdownActor: ActorRef) extends Actor
  with ActorLogging {
  /** A list with the currently available shutdown listeners. */
  private var listeners = List.empty[ShutdownListener]

  override def receive: Receive = {
    case AddShutdownListener(listener) =>
      listeners = listener :: listeners

    case RemoveShutdownListener(listener) =>
      listeners = listeners filterNot (_ == listener)

    case p: ShutdownManagementActor.Process =>
      val regId = messageBus.registerListener {
        case ProcessData(process, services, registrationID) =>
          messageBus removeListener registrationID
          if (processServices(services)) {
            shutdownActor ! p
          }
      }
      messageBus publish ProcessData(p, listeners, regId)
  }

  /**
    * Processes the given collection of services.
    *
    * @param services the services to be processed
    * @return a flag whether processing was successful
    */
  private def processServices(services: Iterable[ShutdownListener]): Boolean = {
    val optVeto = services find (!_.onShutdown())
    optVeto.isEmpty
  }
}
