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
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ShutdownHandler''.
  */
class ShutdownHandlerSpec extends FlatSpec with Matchers with MockitoSugar {
  "A ShutdownHandler" should "shutdown the platform directly if there is no observer" in {
    val helper = new HandlerTestHelper

    helper.triggerShutdown()
      .expectShutdown()
  }

  it should "not shutdown the platform for an invalid Shutdown message" in {
    val helper = new HandlerTestHelper

    helper.triggerShutdown(ctx = mock[ClientApplicationContext])
      .expectNoShutdown()
  }

  it should "wait for confirmations of observers before shutting down" in {
    val helper = new HandlerTestHelper
    val obs1 = helper.registerObserver()
    val obs2 = helper.registerObserver()

    helper.triggerShutdown()
      .expectNoShutdown()
      .confirmShutdown(obs2)
      .confirmShutdown(obs1)
      .expectShutdown()
  }

  it should "handler messages to remove observers" in {
    val helper = new HandlerTestHelper
    val obs1 = helper.registerObserver()
    val obs2 = helper.registerObserver()

    helper.removeObserver(obs2)
      .triggerShutdown()
      .confirmShutdown(obs1)
      .expectShutdown()
  }

  it should "ignore shutdown confirmations if no shutdown is in progress" in {
    val helper = new HandlerTestHelper
    val obs = helper.registerObserver()

    helper.confirmShutdown(obs)
      .triggerShutdown()
      .expectNoShutdown()
  }

  it should "ignore multiple shutdown triggers" in {
    val helper = new HandlerTestHelper

    helper.triggerShutdown()
      .triggerShutdown()
      .expectShutdown()
  }

  it should "handle a remove observer operation while shutdown is in progress" in {
    val helper = new HandlerTestHelper
    val obs = helper.registerObserver()

    helper.triggerShutdown()
      .removeObserver(obs)
      .expectShutdown()
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class HandlerTestHelper {
    /** Mock for the management application. */
    private val application = mock[ClientManagementApplication]

    /** The handler to be tested. */
    private val handler = new ShutdownHandler(application)

    /**
      * Sends a shutdown message to the handler under test.
      *
      * @param ctx the context to be passed to the handler
      * @return this test helper
      */
    def triggerShutdown(ctx: ClientApplicationContext = application): HandlerTestHelper = {
      send(ShutdownHandler.Shutdown(ctx))
      this
    }

    /**
      * Verifies that a shutdown has been executed.
      *
      * @return this test helper
      */
    def expectShutdown(): HandlerTestHelper = {
      verify(application).shutdown()
      this
    }

    /**
      * Verifies that no shutdown operation has been completed yet.
      *
      * @return this test helper
      */
    def expectNoShutdown(): HandlerTestHelper = {
      verify(application, never()).shutdown()
      this
    }

    /**
      * Registers a shutdown observer at the test handler and returns its
      * component ID.
      *
      * @return the component ID of the new observer
      */
    def registerObserver(): ComponentID = {
      val id = ComponentID()
      send(ShutdownHandler.RegisterShutdownObserver(id))
      id
    }

    /**
      * Sends a shutdown confirmation message for the specified observer to the
      * test handler.
      *
      * @param observerID the observer ID
      * @return this test helper
      */
    def confirmShutdown(observerID: ComponentID): HandlerTestHelper = {
      send(ShutdownHandler.ShutdownDone(observerID))
      this
    }

    /**
      * Removes the observer with the specified ID from the test handler.
      *
      * @param observerID the observer ID
      * @return this test helper
      */
    def removeObserver(observerID: ComponentID): HandlerTestHelper = {
      send(ShutdownHandler.RemoveShutdownObserver(observerID))
      this
    }

    /**
      * Sens the specified message to the test handler.
      *
      * @param msg the message
      * @return this test helper
      */
    private def send(msg: Any): HandlerTestHelper = {
      handler receive msg
      this
    }
  }

}
