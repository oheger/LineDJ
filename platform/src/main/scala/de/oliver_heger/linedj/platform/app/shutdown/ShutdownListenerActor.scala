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

import akka.actor.ActorRef
import de.oliver_heger.linedj.platform.app.ShutdownListener
import de.oliver_heger.linedj.platform.app.shutdown.BaseShutdownActor.Process
import de.oliver_heger.linedj.platform.app.shutdown.ShutdownListenerActor.{AddShutdownListener, RemoveShutdownListener}
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
class ShutdownListenerActor(messageBus: MessageBus, shutdownActor: ActorRef)
  extends BaseShutdownActor[ShutdownListener](messageBus) {
  override protected def customReceive: Receive = {
    case AddShutdownListener(listener) =>
      addService(listener)

    case RemoveShutdownListener(listener) =>
      removeService(listener)
  }

  /**
    * @inheritdoc This implementation returns a processing function which
    *             invokes the listener's ''onShutdown()'' method and returns
    *             the result.
    */
  override protected def processFunction: (Process, ShutdownListener) => Boolean =
    (_, listener) => listener.onShutdown()

  /**
    * @inheritdoc This implementation sends the current ''Process'' message to
    *             the shutdown management actor.
    */
  override protected def afterProcessing(process: Process): Unit = {
    shutdownActor ! process
  }
}
