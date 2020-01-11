/*
 * Copyright 2015-2020 The Developers Team.
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

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.ArchiveAvailabilityRegistration
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler

object RemoteController {
  /**
    * The group for the actions depending on a server available. This group is
    * disabled if no server connection is established.
    */
  val ServerActions = "SERVER_ACTIONS"
}

/**
  * A controller class which monitors the server state and updates some UI
  * elements accordingly.
  *
  * This class registers a consumer at the archive extension that generates
  * events related to changes of the server availability. When the server state
  * changes this should be reflected in the UI: an indicator showing the
  * current state is switched; some actions are only enabled if the server is
  * available.
  *
  * Instances are typically used by applications interacting with the media
  * archive. In such a scenario, they are declared in the script for the
  * owning applications's main window.
  *
  * @param actionStore                the action store
  * @param serverAvailableIndicator   the indicator for the server available
  * @param serverUnavailableIndicator the indicator for the server not available
  */
class RemoteController(actionStore: ActionStore,
                       serverAvailableIndicator: WidgetHandler,
                       serverUnavailableIndicator: WidgetHandler)
  extends ConsumerRegistrationProvider {

  import RemoteController._

  /** The consumer registrations used for this controller. */
  override lazy val registrations = createRegistrations()

  /**
    * The consumer function invoked when the availability state of the media
    * archive changes. Here the corresponding adaptations on the UI are
    * implemented.
    *
    * @param ev the change event
    */
  private def availabilityChanged(ev: MediaFacade.MediaArchiveAvailabilityEvent): Unit =
  ev match {
    case MediaFacade.MediaArchiveUnavailable =>
      actionStore.enableGroup(ServerActions, false)
      serverAvailableIndicator setVisible false
      serverUnavailableIndicator setVisible true

    case MediaFacade.MediaArchiveAvailable =>
      actionStore.enableGroup(ServerActions, true)
      serverAvailableIndicator setVisible true
      serverUnavailableIndicator setVisible false
  }

  /**
    * Creates the consumer registrations used by this controller. There is a
    * registration for the archive available extension.
    *
    * @return the collection of registrations
    */
  private def createRegistrations(): Iterable[ConsumerRegistration[_]] =
  List(ArchiveAvailabilityRegistration(id = ComponentID(),
    callback = availabilityChanged))
}
