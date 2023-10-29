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

import de.oliver_heger.linedj.platform.app.ShutdownHandler.{ShutdownCompletionNotifier, ShutdownObserver}
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.utils.ActorFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''ShutdownHandler''.
  */
class ShutdownHandlerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ShutdownHandlerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  "A ShutdownHandler" should "shutdown the platform directly if there is no observer" in {
    val helper = new HandlerTestHelper

    helper.triggerShutdown()
      .expectShutdown()
      .expectNoActorCreation()
  }

  it should "not shutdown the platform for an invalid Shutdown message" in {
    val helper = new HandlerTestHelper

    helper.triggerShutdown(ctx = mock[ClientApplicationContext])
      .expectNoActorCreation()
      .expectNoShutdown()
  }

  it should "create a correct shutdown management actor to manage the shutdown operation" in {
    val helper = new HandlerTestHelper
    val obs1 = helper.registerObserver()
    val obs2 = helper.registerObserver()

    helper.triggerShutdown()
      .expectNoShutdown()
      .verifyShutdownActor(Set(obs1.componentID, obs2.componentID))
  }

  it should "handle messages to remove observers" in {
    val helper = new HandlerTestHelper
    val obs1 = helper.registerObserver()
    val obs2 = helper.registerObserver()

    helper.removeObserver(obs2)
      .triggerShutdown()
      .verifyShutdownActor(Set(obs1.componentID))
    obs2.verifyNoShutdownTriggered()
  }

  it should "ignore multiple shutdown triggers" in {
    val helper = new HandlerTestHelper

    helper.triggerShutdown()
      .triggerShutdown()
      .expectShutdown()
  }

  it should "ignore multiple shutdown triggers when observers are registered" in {
    val helper = new HandlerTestHelper
    val observer = helper.registerObserver()

    helper.triggerShutdown()
      .triggerShutdown()
      .verifyShutdownActor(Set(observer.componentID))
  }

  it should "provide a correct notifier to confirm a complete shutdown" in {
    val helper = new HandlerTestHelper
    val obs1 = helper.registerObserver()
    val obs2 = helper.registerObserver()

    helper.triggerShutdown()
    obs1.handleShutdownTrigger()
    obs2.handleShutdownTrigger()
    helper.expectShutdownConfirmationToActor(obs1)
      .expectShutdownConfirmationToActor(obs2)
  }

  it should "handle a remove observer operation while shutdown is in progress" in {
    val helper = new HandlerTestHelper
    helper.registerObserver()
    val obs2 = helper.registerObserver()

    helper.triggerShutdown()
      .removeObserver(obs2)
      .expectShutdownConfirmationToActor(obs2)
  }

  /**
    * A data class storing information about a shutdown observer used by a test
    * case.
    *
    * @param componentID the component ID of the observer
    * @param observer    the mock observer
    */
  private case class ShutdownObserverData(componentID: ComponentID,
                                          observer: ShutdownObserver) {
    /**
      * Verifies that this observer was invoked to trigger its shutdown.
      *
      * @return the notifier passed to this observer
      */
    def verifyShutdownTriggered(): ShutdownCompletionNotifier = {
      val captor = ArgumentCaptor.forClass(classOf[ShutdownCompletionNotifier])
      verify(observer).triggerShutdown(captor.capture())
      captor.getValue
    }

    /**
      * Verifies that the wrapped observer has not been asked to shutdown.
      */
    def verifyNoShutdownTriggered(): Unit = {
      verify(observer, never()).triggerShutdown(any())
    }

    /**
      * Simulates a shutdown operation. Verifies that shutdown was triggered
      * and invokes the shutdown notifier.
      */
    def handleShutdownTrigger(): Unit = {
      val notifier = verifyShutdownTriggered()
      notifier.shutdownComplete()
    }
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class HandlerTestHelper {
    /** Test probe for the shutdown management actor. */
    private val probeShutdownActor = TestProbe()

    /** Mock for the management application. */
    private val application = createMockApplication()

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
      * Registers a shutdown observer at the test handler and returns a data
      * object for it.
      *
      * @return a data object for the new observer
      */
    def registerObserver(): ShutdownObserverData = {
      val id = ComponentID()
      val observer = mock[ShutdownObserver]
      send(ShutdownHandler.RegisterShutdownObserver(id, observer))
      ShutdownObserverData(id, observer)
    }

    /**
      * Removes the specified observer from the test handler.
      *
      * @param observer the observer
      * @return this test helper
      */
    def removeObserver(observer: ShutdownObserverData): HandlerTestHelper = {
      send(ShutdownHandler.RemoveShutdownObserver(observer.componentID))
      this
    }

    /**
      * Checks that no actor has been created by the handler under test.
      *
      * @return this test helper
      */
    def expectNoActorCreation(): HandlerTestHelper = {
      verifyNoInteractions(application.actorFactory)
      this
    }

    /**
      * Returns the ''Props'' used by the handler under test to create the
      * shutdown management actor.
      *
      * @return the ''Props'' to create the shutdown management actor
      */
    def shutdownActorProps: Props = {
      val captor = ArgumentCaptor.forClass(classOf[Props])
      verify(application.actorFactory).createActor(captor.capture(), argEq(ShutdownHandler.ShutdownActorName))
      captor.getValue
    }

    /**
      * Verifies that a correct shutdown management actor has been created.
      *
      * @param expComponents the expected pending observer IDs
      * @return this test helper
      */
    def verifyShutdownActor(expComponents: Set[ComponentID]): HandlerTestHelper = {
      val props = shutdownActorProps
      val shutdownActor = TestActorRef[ShutdownManagementActor](props)
      shutdownActor.underlyingActor.managementApp should be(application)
      shutdownActor.underlyingActor.pendingComponents should be(expComponents)
      this
    }

    /**
      * Checks that a message confirming the shutdown of the given observer was
      * sent to the shutdown actor.
      *
      * @param observer the observer in question
      * @return this test helper
      */
    def expectShutdownConfirmationToActor(observer: ShutdownObserverData): HandlerTestHelper = {
      probeShutdownActor.expectMsg(ShutdownManagementActor.ShutdownConfirmation(observer.componentID))
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

    /**
      * Creates an initialized mock for the client management application. The
      * application is prepared to create the shutdown management actor.
      *
      * @return the mock application
      */
    private def createMockApplication(): ClientManagementApplication = {
      val app = mock[ClientManagementApplication]
      val factory = createActorFactory()
      when(app.actorFactory).thenReturn(factory)
      when(app.managementConfiguration).thenReturn(new PropertiesConfiguration)
      app
    }

    /**
      * Creates a mock for the actor factory. The factory returns a test probe
      * for the shutdown management actor.
      *
      * @return the actor factory mock
      */
    private def createActorFactory(): ActorFactory = {
      val factory = mock[ActorFactory]
      when(factory.createActor(any[Props](), argEq(ShutdownHandler.ShutdownActorName)))
        .thenReturn(probeShutdownActor.ref)
      factory
    }
  }

}
