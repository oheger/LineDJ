/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.client.bus

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.client.remoting.RemoteRelayActor
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
 * This class is registered as listener at the message bus and reacts on events
 * related to changes of the server availability. When the server state changes
 * this should be reflected in the UI: an indicator showing the current state
 * is switched; some actions are only enabled if the server is available.
 *
 * An instance of this class is created by the dependency injection
 * framework. The dependencies are automatically injected.
 *
 * @param actionStore the action store
 * @param serverAvailableIndicator the indicator for the server available
 * @param serverUnavailableIndicator the indicator for the server not available
 */
class RemoteController(actionStore: ActionStore, serverAvailableIndicator: WidgetHandler,
                       serverUnavailableIndicator: WidgetHandler) extends MessageBusListener {

  import RemoteController._

  /**
   * Returns the function for handling messages published on the message bus.
   * @return the message handling function
   */
  override def receive: Receive = {
    case RemoteRelayActor.ServerUnavailable =>
      actionStore.enableGroup(ServerActions, false)
      serverAvailableIndicator setVisible false
      serverUnavailableIndicator setVisible true

    case RemoteRelayActor.ServerAvailable =>
      actionStore.enableGroup(ServerActions, true)
      serverAvailableIndicator setVisible true
      serverUnavailableIndicator setVisible false
  }

  /**
   * A simulated set method which is invoked by the dependency injection
   * framework. The only purpose of this method is to trigger the instantiation
   * of the message bus registration bean. This causes all listeners to be
   * registered at the message bus. The reference to this bean is not stored
   * because it is not needed.
   * @param reg the ''MessageBusRegistration'' bean
   */
  def setMessageBusRegistration(reg: MessageBusRegistration): Unit = {
    //do nothing
  }
}
