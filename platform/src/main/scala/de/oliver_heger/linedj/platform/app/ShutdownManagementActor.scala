/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import de.oliver_heger.linedj.platform.app.ShutdownManagementActor.{ShutdownConfirmation, ShutdownTimeout}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.utils.SchedulerSupport

import scala.concurrent.duration.DurationInt

object ShutdownManagementActor {

  /**
    * A message handled by [[ShutdownManagementActor]] confirming that the
    * component with the ID specified has completed its shutdown operation.
    *
    * After such messages have been received for all components pending, the
    * platform can be shut down.
    *
    * @param componentID the component ID
    */
  case class ShutdownConfirmation(componentID: ComponentID)

  /**
    * An internal message indicating that the timeout of the shutdown operation
    * was reached.
    */
  private case object ShutdownTimeout

  /**
    * Returns a ''Props'' object to create an instance of this actor class.
    *
    * @param managementApp     the client management application
    * @param pendingComponents the components to monitor
    * @return the ''Props'' to create an actor instance
    */
  def props(managementApp: ClientManagementApplication, pendingComponents: Set[ComponentID]): Props =
    Props(new ShutdownManagementActor(managementApp, pendingComponents) with SchedulerSupport)
}

/**
  * An actor class that manages an extended shutdown operation.
  *
  * This actor is part of the implementation of the extended shutdown logic.
  * It is notified when the shutdown of the platform is triggered with the
  * current set of registered shutdown observer IDs. It then waits for shutdown
  * confirmations of all these observers. After they have arrived, the
  * management application is shutdown - which is equivalent to the platform
  * shutdown.
  *
  * For the platform shutdown a timeout can be configured. If not all the
  * observers send a confirmation within this time span, the platform is
  * shutdown anyway.
  *
  * @param managementApp     the client management application
  * @param pendingComponents the set of registered shutdown observers
  */
class ShutdownManagementActor(val managementApp: ClientManagementApplication,
                              val pendingComponents: Set[ComponentID]) extends Actor with ActorLogging {
  this: SchedulerSupport =>

  /**
    * Stores the current set of components to monitor for shutdown
    * confirmation.
    */
  private var monitoredComponents = pendingComponents

  /**
    * Stores the handle to the scheduled timeout notification. It needs to be
    * cancelled when shutdown is complete.
    */
  private var cancellable: Cancellable = _

  override def preStart(): Unit = {
    super.preStart()
    val timeout = managementApp.managementConfiguration.getInt(ClientManagementApplication.PropShutdownTimeout,
      ClientManagementApplication.DefaultShutdownTimeoutMillis).millis
    cancellable = scheduleMessageOnce(timeout, self, ShutdownTimeout)
  }

  override def receive: Receive = {
    case ShutdownConfirmation(componentID) =>
      log.info("Receiving shutdown confirmation for component {}.", componentID)
      if (monitoredComponents.nonEmpty) {
        monitoredComponents -= componentID
        if (monitoredComponents.isEmpty) {
          log.info("All shutdown confirmations received. Shutting down management application.")
          cancellable.cancel()
          managementApp.shutdown()
        }
      } else {
        log.warning("Unexpected shutdown confirmation for component {}.", componentID)
      }

    case ShutdownTimeout =>
      log.warning("Shutdown operation timed out. Forcing shutdown.")
      managementApp.shutdown()
  }
}
