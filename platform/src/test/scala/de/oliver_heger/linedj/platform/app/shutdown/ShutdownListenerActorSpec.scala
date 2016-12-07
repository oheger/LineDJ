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

import akka.actor.Actor.Receive
import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.platform.app.ShutdownListener
import net.sf.jguiraffe.gui.app.ApplicationShutdownListener
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}

/**
  * Test class for ''ShutdownListenerActor''.
  */
class ShutdownListenerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  FlatSpecLike with BeforeAndAfterAll with MockitoSugar {
  def this() = this(ActorSystem("ShutdownListenerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a mock for a shutdown listener that returns the specified result
    * when invoked.
    *
    * @param allowShutdown flag whether shutdown should be allowed
    * @return the prepared listener mock
    */
  private def createListener(allowShutdown: Boolean): ShutdownListener = {
    val listener = mock[ShutdownListener]
    when(listener.onShutdown()).thenReturn(allowShutdown)
    listener
  }

  "A ShutdownListenerActor" should "process registered shutdown listeners" in {
    val listener1 = createListener(allowShutdown = true)
    val listener2 = createListener(allowShutdown = true)
    val helper = new ShutdownListenerActorTestHelper

    helper.receive(ShutdownListenerActor.AddShutdownListener(listener1))
      .receive(ShutdownListenerActor.AddShutdownListener(listener2))
      .receive(BaseShutdownActor.Process(null))
      .expectAndInvokeBusListener()
      .expectRemoveBusListener()
    verify(listener1).onShutdown()
    verify(listener2).onShutdown()
  }

  it should "notify the shutdown manager actor after successful processing" in {
    val listener = createListener(allowShutdown = true)
    val process = BaseShutdownActor.Process(mock[ApplicationShutdownListener])
    val helper = new ShutdownListenerActorTestHelper

    helper.receive(ShutdownListenerActor.AddShutdownListener(listener))
      .receive(process)
      .expectAndInvokeBusListener()
    helper.probeShutdownManager.expectMsg(process)
  }

  it should "cancel the operation if a listener gives a veto" in {
    val listener1 = createListener(allowShutdown = true)
    val listener2 = createListener(allowShutdown = false)
    val helper = new ShutdownListenerActorTestHelper

    helper.receive(ShutdownListenerActor.AddShutdownListener(listener1))
      .receive(ShutdownListenerActor.AddShutdownListener(listener2))
      .receive(BaseShutdownActor.Process(null))
      .expectAndInvokeBusListener()
    verify(listener1, never()).onShutdown()
    val msg = "PING"
    helper.probeShutdownManager.ref ! msg
    helper.probeShutdownManager.expectMsg(msg)
  }

  it should "support removing shutdown listeners" in {
    val listener1 = createListener(allowShutdown = true)
    val listener2 = createListener(allowShutdown = true)
    val helper = new ShutdownListenerActorTestHelper

    helper.receive(ShutdownListenerActor.AddShutdownListener(listener1))
      .receive(ShutdownListenerActor.AddShutdownListener(listener2))
      .receive(ShutdownListenerActor.RemoveShutdownListener(listener2))
      .receive(BaseShutdownActor.Process(null))
      .expectAndInvokeBusListener()
    verify(listener1).onShutdown()
    verify(listener2, never()).onShutdown()
  }

  it should "reset the processOngoing flag after processing" in {
    val listener = createListener(allowShutdown = false)
    val helper = new ShutdownListenerActorTestHelper
    helper.receive(ShutdownListenerActor.AddShutdownListener(listener))
      .receive(BaseShutdownActor.Process(null))
      .expectAndInvokeBusListener()

    helper.receive(BaseShutdownActor.Process(null))
      .expectAndInvokeBusListener()
    verify(helper.bus, times(2)).registerListener(any(classOf[Receive]))
  }

  /**
    * A test helper class managing the dependencies of the test actor.
    */
  private class ShutdownListenerActorTestHelper
    extends ShutdownActorTestHelper[ShutdownListenerActor] {
    /** Test probe for the shutdown management actor. */
    val probeShutdownManager: TestProbe = TestProbe()

    /** The actor to be tested. */
    override val actor: TestActorRef[ShutdownListenerActor] =
      TestActorRef[ShutdownListenerActor](Props(classOf[ShutdownListenerActor],
        bus, probeShutdownManager.ref))
  }

}
