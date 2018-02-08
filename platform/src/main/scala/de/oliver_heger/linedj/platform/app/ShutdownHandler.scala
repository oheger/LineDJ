/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.platform.app

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.app.ShutdownHandler.{RegisterShutdownObserver,
  RemoveShutdownObserver, Shutdown, ShutdownDone}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import org.slf4j.LoggerFactory

object ShutdownHandler {

  /**
    * A message processed by [[ShutdownHandler]] that causes the
    * shutdown of the current system. This message is typically sent by a
    * component responsible for shutdown handling. The current application
    * context must be provided to ensure that this message was not sent from
    * an external system (e.g. via a remote connection); the application
    * context should only be known in the local application.
    *
    * Components registered as shutdown observers must handle and confirm
    * this message before the platform can go down.
    *
    * @param applicationContext the ''ClientApplicationContext''
    */
  case class Shutdown(applicationContext: ClientApplicationContext)

  /**
    * A message processed by [[ShutdownHandler]] that registers a shutdown
    * observer. This message causes the handler to wait for a shutdown
    * confirmation from this observer before actually shutting down the
    * platform.
    *
    * @param observerID the ID of the observer
    */
  case class RegisterShutdownObserver(observerID: ComponentID)

  /**
    * A message processed by [[ShutdownHandler]] that removes the registration
    * of a shutdown observer. During a shutdown operation, the handler will no
    * longer wait for a confirmation of this observer.
    *
    * @param observerID the ID of the observer to be removed
    */
  case class RemoveShutdownObserver(observerID: ComponentID)

  /**
    * A message processed by [[ShutdownHandler]] telling it that the specified
    * shutdown observer has completed its shutdown handling. When such messages
    * have arrived for all registered components the system can actually go
    * down.
    *
    * @param observerID the ID of the observer
    */
  case class ShutdownDone(observerID: ComponentID)

}

/**
  * A class responsible for a controlled shutdown of the LineDJ platform.
  *
  * During shutdown some components may have to execute some specific steps
  * like saving their current state to disk. Such actions may be hard to be
  * implemented using normal OSGi deactivation logic: It has to be ensured that
  * all dependent services are still available that may be needed for a
  * graceful shutdown operation. It may also be the case that asynchronous
  * calls need to be executed which would have to be synchronized with the OSGi
  * management thread.
  *
  * To simplify shutdown handling and make it more robust, this class
  * introduces another concept for shutdown handling. Components that have to
  * execute steps during a shutdown operation can register at this component by
  * publishing a ''RegisterShutdownObserver'' message on the system message
  * bus. If there is at least one observer registered, a shutdown operation
  * will not be executed immediately, but the handler waits for confirmations
  * from all registered shutdown observers. So an observer must handle a
  * ''Shutdown'' message and eventually answer it with a ''ShutdownDone''
  * message. Only after all observers have sent such a ''ShutdownDone''
  * message, the platform is actually shut down.
  *
  * @param app the management application
  */
class ShutdownHandler(app: ClientManagementApplication) extends MessageBusListener {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** Stores the IDs of shutdown observers registered at this component. */
  private var observers = Set.empty[ComponentID]

  /** A flag whether a shutdown operation is ongoing. */
  private var shutdownInProgress = false

  /**
    * Returns the function for handling messages published on the message bus.
    *
    * @return the message handling function
    */
  override def receive: Receive = {
    case Shutdown(ctx) =>
      log.info("Received Shutdown command.")
      if (ctx == app) {
        if (!shutdownInProgress) {
          initiateShutdown()
        } else log.info("Ignoring Shutdown command while shutdown is in progress.")
      }
      else log.warn("Ignoring invalid Shutdown command: " + ctx)

    case RegisterShutdownObserver(id) =>
      log.info("Added shutdown observer " + id)
      observers += id

    case RemoveShutdownObserver(id) =>
      observers -= id
      log.info("Removed shutdown observer " + id)
      if (shutdownInProgress) shutdownIfPossible()

    case ShutdownDone(id) =>
      log.info("Received shutdown confirmation from " + id)
      if (shutdownInProgress) {
        observers -= id
        shutdownIfPossible()
      }
  }

  /**
    * Initiates a shutdown operation in reaction of a ''Shutdown'' message.
    * If possible, the platform is shutdown immediately; otherwise, waiting for
    * confirmation messages starts.
    */
  private def initiateShutdown(): Unit = {
    shutdownInProgress = true
    if (observers.isEmpty) {
      shutdownPlatform()
    }
  }

  /**
    * Shuts down the platform if all observers have sent their confirmation.
    */
  private def shutdownIfPossible(): Unit = {
    if (observers.isEmpty) shutdownPlatform()
  }

  /**
    * Shuts down the whole platform. This method is called when all
    * prerequisites for a shutdown operation are fulfilled.
    */
  private def shutdownPlatform(): Unit = {
    app.shutdown()
  }
}
