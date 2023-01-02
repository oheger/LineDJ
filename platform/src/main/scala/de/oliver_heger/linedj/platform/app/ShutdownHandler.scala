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

package de.oliver_heger.linedj.platform.app

import akka.actor.Actor.Receive
import akka.actor.ActorRef
import de.oliver_heger.linedj.platform.app.ShutdownHandler._
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import org.apache.logging.log4j.LogManager

object ShutdownHandler {
  /** The name used for the shutdown management actor. */
  final val ShutdownActorName = "shutdownManagementActor"

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
    * @param observer the observer to register
    */
  case class RegisterShutdownObserver(observerID: ComponentID,
                                      observer: ShutdownObserver)

  /**
    * A message processed by [[ShutdownHandler]] that removes the registration
    * of a shutdown observer. During a shutdown operation, the handler will no
    * longer wait for a confirmation of this observer.
    *
    * @param observerID the ID of the observer to be removed
    */
  case class RemoveShutdownObserver(observerID: ComponentID)

  /**
    * A trait defining a mechanism to send a notification when a shutdown
    * operation is complete.
    *
    * An object implementing this trait is passed to a [[ShutdownObserver]]
    * when a shutdown is to be triggered. The observer can now initiate its
    * (potentially asynchronous) shutdown operation. When this is done it must
    * call the notification method to indicate that it has finished its
    * shutdown action. After all observers have sent this notification, the
    * platform can actually shut down.
    */
  trait ShutdownCompletionNotifier {
    /**
      * Notifies the shutdown handler that the shutdown logic of the current
      * observer has been completed. Note that this method can be called from
      * an arbitrary thread.
      */
    def shutdownComplete(): Unit
  }

  /**
    * A trait defining the interface of a component that takes part in the
    * extended shutdown mechanism implemented by [[ShutdownHandler]].
    *
    * When the shutdown of the platform is requested the [[ShutdownHandler]]
    * invokes the single method of this trait in the event dispatch thread. An
    * implementation can then execute its specific shutdown logic. When this is
    * done, the ''ShutdownCompletionNotifier'' provided must be invoked.
    */
  trait ShutdownObserver {
    /**
      * Notifies this observer that the platform is going to be shutdown. This
      * method is called in the event dispatch thread. An implementation can
      * now perform arbitrary shutdown logic, synchronously or asynchronously
      * as it pleases. Afterwards the ''ShutdownCompletionNotifier'' passed to
      * this method must be invoked.
      * @param completionNotifier the notifier to indicate that shutdown is
      *                           complete for this observer
      */
    def triggerShutdown(completionNotifier: ShutdownCompletionNotifier): Unit
  }
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
  * will not be executed immediately, but the handler invokes the
  * ''triggerShutdown()'' method of all observers and waits for their
  * confirmations to arrive. So an observer must implement this method to
  * execute the shutdown logic it requires and eventually invoke the
  * [[ShutdownCompletionNotifier]] provided. Only after all observers have sent
  * such a notification, the platform is actually shut down.
  *
  * @param app the management application
  */
class ShutdownHandler(app: ClientManagementApplication) extends MessageBusListener {
  /** The logger. */
  private val log = LogManager.getLogger(getClass)

  /** Stores the shutdown observers registered at this component. */
  private var observers = Map.empty[ComponentID, ShutdownObserver]

  /** Stores the reference to the shutdown management actor if needed. */
  private var optShutdownActor: Option[ActorRef] = None

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

    case RegisterShutdownObserver(id, observer) =>
      log.info("Added shutdown observer " + id)
      observers += id -> observer

    case RemoveShutdownObserver(id) =>
      observers -= id
      log.info("Removed shutdown observer " + id)
      if (shutdownInProgress) {
        optShutdownActor foreach { _ ! ShutdownManagementActor.ShutdownConfirmation(id) }
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
    } else {
      log.info("Creating ShutdownManagementActor to monitor these shutdown observers: {}.", observers.keySet)
      val props = ShutdownManagementActor.props(app, observers.keySet)
      val shutdownActor = app.actorFactory.createActor(props, ShutdownActorName)
    observers foreach { entry =>
      val notifier = new ShutdownCompletionNotifier {
        override def shutdownComplete(): Unit = {
          shutdownActor ! ShutdownManagementActor.ShutdownConfirmation(entry._1)
        }
      }
      entry._2.triggerShutdown(notifier)
    }
      optShutdownActor = Some(shutdownActor)
    }
  }

  /**
    * Shuts down the whole platform. This method is called when all
    * prerequisites for a shutdown operation are fulfilled.
    */
  private def shutdownPlatform(): Unit = {
    app.shutdown()
  }
}
