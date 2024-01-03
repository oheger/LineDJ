/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerFunction
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaArchiveAvailabilityEvent
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.ArchiveAvailabilityRegistration
import net.sf.jguiraffe.gui.builder.action.ActionStore
import net.sf.jguiraffe.gui.builder.components.WidgetHandler
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''RemoteController''.
 */
class RemoteControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  "A RemoteController" should "react on a server not available message" in:
    val helper = new RemoteControllerTestHelper
    helper receive MediaFacade.MediaArchiveUnavailable

    verify(helper.actionStore).enableGroup(RemoteController.ServerActions, false)
    verify(helper.availableIndicator).setVisible(false)
    verify(helper.unavailableIndicator).setVisible(true)

  it should "react on a server available message" in:
    val helper = new RemoteControllerTestHelper
    helper receive MediaFacade.MediaArchiveAvailable

    verify(helper.actionStore).enableGroup(RemoteController.ServerActions, true)
    verify(helper.availableIndicator).setVisible(true)
    verify(helper.unavailableIndicator).setVisible(false)

  /**
   * A helper class managing a test object with its dependencies.
   */
  private class RemoteControllerTestHelper:
    /** The action store mock. */
    val actionStore: ActionStore = mock[ActionStore]

    /** The mock for the server available indicator. */
    val availableIndicator: WidgetHandler = mock[WidgetHandler]

    /** The mock for the server unavailable indicator. */
    val unavailableIndicator: WidgetHandler = mock[WidgetHandler]

    /** The test instance. */
    val controller = new RemoteController(actionStore = actionStore,
      serverAvailableIndicator = availableIndicator,
      serverUnavailableIndicator = unavailableIndicator)

    /** The consumer function used by the controller. */
    private lazy val consumerFunction = fetchConsumerFunction()

    /**
     * Sends a message to the receive method of the test controller.
     * @param msg the message to be sent
     */
    def receive(msg: MediaArchiveAvailabilityEvent): Unit =
      consumerFunction(msg)

    /**
      * Obtains the consumer function from the controller's consumer
      * registration.
      *
      * @return the consumer function
      */
    private def fetchConsumerFunction(): ConsumerFunction[MediaArchiveAvailabilityEvent] =
      controller.registrations should have size 1
      val reg = ConsumerRegistrationProviderTestHelper
        .findRegistration[ArchiveAvailabilityRegistration](controller)
      reg.id should not be null
      reg.callback

