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

package de.oliver_heger.linedj.platform.app.shutdown

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientManagementApplication}
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.app.{Application, ApplicationShutdownListener}
import org.mockito.{ArgumentCaptor, Mockito}
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object ShutdownManagementActorSpec {
  /** Registration ID for the message bus. */
  private val RegistrationID = 20161203
}

/**
  * Test class for ''ShutdownManagementActor''.
  */
class ShutdownManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ShutdownManagementActorSpec._

  def this() = this(ActorSystem("ShutdownManagementActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A ShutdownManagementActor" should "shutdown all active applications" in {
    val app1 = mock[Application]
    val app2 = mock[Application]
    val helper = new ShutdownManagementActorTestHelper

    helper.receive(ShutdownManagementActor.AddApplication(app1))
      .receive(ShutdownManagementActor.AddApplication(app2))
      .receive(ShutdownManagementActor.Process(null))
      .expectAndInvokeBusListener()
      .expectRemoveBusListener()
    verify(app1).shutdown()
    verify(app2).shutdown()
  }

  it should "catch exceptions during service processing" in {
    val app1 = mock[Application]
    val app2 = mock[Application]
    doThrow(new RuntimeException("ShutdownEx1")).when(app1).shutdown()
    doThrow(new RuntimeException("ShutdownEx2")).when(app2).shutdown()
    val helper = new ShutdownManagementActorTestHelper

    helper.receive(ShutdownManagementActor.AddApplication(app1))
      .receive(ShutdownManagementActor.AddApplication(app2))
      .receive(ShutdownManagementActor.Process(null))
      .expectAndInvokeBusListener()
    verify(app1).shutdown()
    verify(app2).shutdown()
  }

  it should "support removing applications" in {
    val app1 = mock[Application]
    val app2 = mock[Application]
    val helper = new ShutdownManagementActorTestHelper

    helper.receive(ShutdownManagementActor.AddApplication(app1))
      .receive(ShutdownManagementActor.AddApplication(app2))
      .receive(ShutdownManagementActor.RemoveApplication(app2))
      .receive(ShutdownManagementActor.Process(null))
      .expectAndInvokeBusListener()
    verify(app2, never()).shutdown()
  }

  it should "send a Shutdown message after processing all applications" in {
    val helper = new ShutdownManagementActorTestHelper

    helper.receive(ShutdownManagementActor.AddApplication(mock[Application]))
      .receive(ShutdownManagementActor.Process(null))
      .expectAndInvokeBusListener()
      .expectPublish(ClientManagementApplication.Shutdown(helper.applicationContext))
  }

  it should "remove the special shutdown listener" in {
    val app = mock[Application]
    val listener = mock[ApplicationShutdownListener]
    val helper = new ShutdownManagementActorTestHelper

    helper.receive(ShutdownManagementActor.AddApplication(app))
      .receive(ShutdownManagementActor.Process(listener))
      .expectAndInvokeBusListener()
    val io = Mockito.inOrder(app)
    io.verify(app).removeShutdownListener(listener)
    io.verify(app).shutdown()
  }

  /**
    * Test helper class managing dependencies of the test actor.
    */
  private class ShutdownManagementActorTestHelper {
    /** A mock for the message bus. */
    val bus: MessageBus = createMessageBus()

    /** A mock for the client application context. */
    val applicationContext: ClientApplicationContext = mock[ClientApplicationContext]

    /** The actor to be tested. */
    val actor: TestActorRef[ShutdownManagementActor] =
      TestActorRef[ShutdownManagementActor](Props(classOf[ShutdownManagementActor],
        bus, applicationContext))

    /**
      * Sends a message to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def receive(msg: Any): ShutdownManagementActorTestHelper = {
      actor receive msg
      this
    }

    /**
      * Checks whether a listener has been registered at the message bus and
      * was sent a message. The listener is then invoked with this message.
      *
      * @return this test helper
      */
    def expectAndInvokeBusListener(): ShutdownManagementActorTestHelper = {
      val captListener = ArgumentCaptor.forClass(classOf[Actor.Receive])
      val captMsg = ArgumentCaptor.forClass(classOf[AnyRef])
      val io = Mockito.inOrder(bus)
      io.verify(bus).registerListener(captListener.capture())
      io.verify(bus).publish(captMsg.capture())
      captListener.getValue.apply(captMsg.getValue)
      this
    }

    /**
      * Checks whether the temporary message bus listener has been removed
      * again.
      *
      * @return this test helper
      */
    def expectRemoveBusListener(): ShutdownManagementActorTestHelper = {
      verify(bus).removeListener(RegistrationID)
      this
    }

    /**
      * Checks whether the specified message has been published on the UI
      * message bus.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectPublish(msg: Any): ShutdownManagementActorTestHelper = {
      verify(bus).publish(msg)
      this
    }

    /**
      * Creates the mock for the message bus.
      *
      * @return the mock message bus
      */
    private def createMessageBus(): MessageBus = {
      val bus = mock[MessageBus]
      when(bus.registerListener(any(classOf[Actor.Receive]))).thenReturn(RegistrationID)
      bus
    }
  }

}
