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

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientManagementApplication}
import net.sf.jguiraffe.gui.app.{Application, ApplicationShutdownListener}
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
  * Test class for ''ShutdownManagementActor''.
  */
class ShutdownManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

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
  private class ShutdownManagementActorTestHelper
    extends ShutdownActorTestHelper[ShutdownManagementActor] {
    /** A mock for the client application context. */
    val applicationContext: ClientApplicationContext = mock[ClientApplicationContext]

    /** The actor to be tested. */
    override val actor: TestActorRef[ShutdownManagementActor] =
      TestActorRef[ShutdownManagementActor](Props(classOf[ShutdownManagementActor],
        bus, applicationContext))
  }

}
