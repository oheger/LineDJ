/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.hide

import de.oliver_heger.linedj.platform.app.{ApplicationManager, ClientApplication}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}

/**
  * Data class describing the state of the ''window hiding application
  * manager''. This class contains information about all applications currently
  * available and whether their main windows are visible.
  *
  * @param appsWithTitle a list with all existing applications and the titles
  *                      of their main windows
  * @param visibleApps   a set with the applications whose main windows are
  *                      currently visible
  */
case class ApplicationWindowState(appsWithTitle: Iterable[(ClientApplication, String)],
                                  visibleApps: Set[ClientApplication])

/**
  * A message class representing the registration of a consumer for
  * [[ApplicationWindowState]] information. By sending such a message on the
  * message bus, a consumer can be registered at the ''window hiding
  * application manager''.
  *
  * @param id       the consumer ID
  * @param callback the consumer callback
  */
case class WindowStateConsumerRegistration(override val id: ComponentID,
                                           override val callback:
                                           ConsumerFunction[ApplicationWindowState])
  extends ConsumerRegistration[ApplicationWindowState]:
  override def unRegistration: AnyRef = WindowStateConsumerUnregistration(id)

/**
  * A message class to remove a consumer registration for the
  * [[ApplicationWindowState]].
  *
  * @param id the ID of the consumer to be removed
  */
case class WindowStateConsumerUnregistration(id: ComponentID)

/**
  * A message processed by ''WindowHidingApplicationManager'' that causes the
  * window of the specified application to be shown. If this application's
  * window is already visible, this method has no effect.
  *
  * @param app the application whose window is to be shown
  */
case class ShowApplicationWindow(app: ClientApplication)

/**
  * A message processed by ''WindowHidingApplicationManager'' that causes the
  * window of the specified application to be hidden. If this application's
  * window is not visible, this method has no effect.
  *
  * @param app the application whose window is to be hidden
  */
case class HideApplicationWindow(app: ClientApplication)

/**
  * A message processed by ''WindowHidingApplicationManager'' that triggers a
  * shutdown of the whole platform.
  *
  * Because normal close or exit operations on applications just cause their
  * windows to be hidden, there has to be an explicit way to exit the platform.
  * This is achieved by sending this message to the to the application manager.
  * The passed in reference to an application manager is used to prevent that
  * this message can be sent from outside, e.g. from a remote actor system. A
  * shutdown is triggered only if the reference matches the receiving
  * application manager.
  *
  * @param applicationManager the targeting application manager
  */
case class ExitPlatform(applicationManager: ApplicationManager)
