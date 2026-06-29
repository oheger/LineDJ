/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.platform.archiveclient

import org.apache.pekko.actor.typed.ActorRef

/**
  * A trait providing functionality to monitor a specific state of an archive
  * server. The trait has functions to manage state listeners which it
  * delegates to a monitor actor reference which has to be provided by a
  * concrete implementation.
  *
  * Typically, whether monitoring is enabled or not depends on the platform
  * configuration - if a backoff configuration is defined, it is active. A
  * concrete implementation should therefore evaluate the configuration and,
  * based on the result, returns a defined or undefined `Option` with the
  * reference to the monitor actor.
  *
  * @tparam STATE the type of the state to be passed to change listeners
  */
trait MonitorSupport[STATE]:
  /**
    * Adds a listener that receives notifications when a change in the 
    * monitored state of the managed archive is detected.
    *
    * @param listener the listener to be added
    */
  def addChangeListener(listener: ArchiveStateMonitor.ArchiveChangeListener[STATE]): Unit =
    delegateMessage(ArchiveStateMonitor.ArchiveListenerCommand.AddChangeListener(listener))

  /**
    * Removes the specified change listener.
    *
    * @param listener the listener to be removed
    */
  def removeChangeListener(listener: ArchiveStateMonitor.ArchiveChangeListener[STATE]): Unit =
    delegateMessage(ArchiveStateMonitor.ArchiveListenerCommand.RemoveChangeListener(listener))

  /**
    * Notifies this object that changes in the monitored state are expected.
    * This will cause checks to be performed with a higher frequency, so that
    * changes are likely to be detected soon.
    */
  def expectChanges(): Unit =
    delegateMessage(ArchiveStateMonitor.ArchiveListenerCommand.ChangesExpected())

  /**
    * Returns an `Option` with a reference to the associated monitor actor.
    * This trait uses this function to obtain the reference to the actor to 
    * delegate to on receiving a request. If no such reference is available (if
    * the `Option` is _None_), all function calls are no-ops.
    *
    * @return an `Option` with the reference of the monitor actor
    */
  protected def optMonitorActor: Option[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[STATE]]]

  /**
    * Sends a command to the optional monitor actor if available, otherwise performs no-op.
    *
    * @param message the message to send
    */
  private def delegateMessage(message: ArchiveStateMonitor.ArchiveListenerCommand[STATE]): Unit =
    optMonitorActor.foreach(_.tell(message))