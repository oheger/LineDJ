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

package de.oliver_heger.linedj.client.bus

import de.oliver_heger.linedj.client.mediaifc.{RemoteMessageBus, RemoteRelayActor}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''RemoteController''.
 */
class RemoteControllerSpec extends FlatSpec with Matchers with MockitoSugar {

  "A RemoteController" should "react on a server not available message" in {
    val helper = new RemoteControllerTestHelper
    helper receive RemoteRelayActor.ServerUnavailable

    verify(helper.actionStore).enableGroup(RemoteController.ServerActions, false)
    verify(helper.availableIndicator).setVisible(false)
    verify(helper.unavailableIndicator).setVisible(true)
  }

  it should "react on a server available message" in {
    val helper = new RemoteControllerTestHelper
    helper receive RemoteRelayActor.ServerAvailable

    verify(helper.actionStore).enableGroup(RemoteController.ServerActions, true)
    verify(helper.availableIndicator).setVisible(true)
    verify(helper.unavailableIndicator).setVisible(false)
  }

  /**
   * A helper class managing a test object with its dependencies.
   */
  private class RemoteControllerTestHelper {
    /** The message bus mock. */
    val messageBus = mock[RemoteMessageBus]

    /** The action store mock. */
    val actionStore = mock[ActionStore]

    /** The mock for the server available indicator. */
    val availableIndicator = mock[WidgetHandler]

    /** The mock for the server unavailable indicator. */
    val unavailableIndicator = mock[WidgetHandler]

    /** The test instance. */
    val controller = new RemoteController(actionStore = actionStore, serverAvailableIndicator = availableIndicator, serverUnavailableIndicator =
            unavailableIndicator)

    /**
     * Sends a message to the receive method of the test controller.
     * @param msg the message to be sent
     */
    def receive(msg: Any): Unit = {
      controller.receive(msg)
    }
  }

}
