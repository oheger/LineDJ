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

package de.oliver_heger.linedj.client.remoting

import akka.actor.Actor.Receive
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object RemoteMessageBusSpec {
  /** Constant for test message. */
  private val Message = new Object

  /** Constant for the test message wrapped in a remote message. */
  private val RemoteMessage = RemoteRelayActor.RemoteMessage(RemoteActors.MediaManager, Message)

  /** ID for a message listener registration. */
  private val ListenerID = 20150731
}

/**
 * Test class for ''RemoteMessageBus''.
 */
class RemoteMessageBusSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import RemoteMessageBusSpec._

  def this() = this(ActorSystem("RemoteMessageBusSpec"))

  override protected def afterAll(): Unit = {
    system.shutdown()
    system awaitTermination 10.seconds
  }

  /**
   * Creates the test object, initialized with the test actor serving as relay
   * actor.
   * @return the test message bus
   */
  private def createRemoteBus(): RemoteMessageBus = {
    val bus = mock[MessageBus]
    new RemoteMessageBus(testActor, bus)
  }

  /**
   * Obtains the listener that has been registered on the message bus mock.
   * @param bus the test remote bus
   * @return the listener
   */
  private def fetchListener(bus: RemoteMessageBus): Receive = {
    val captor = ArgumentCaptor.forClass(classOf[Receive])
    verify(bus.bus).registerListener(captor.capture())
    captor.getValue
  }

  /**
   * Prepares the remote bus to expect the registration of a response listener.
   * @param bus the test bus object
   * @return the remote message bus
   */
  private def expectListenerRegistration(bus: RemoteMessageBus): RemoteMessageBus = {
    when(bus.bus.registerListener(any(classOf[Receive]))).thenReturn(ListenerID)
    bus
  }

  "A RemoteMessageBus" should "simplify sending remote messages" in {
    val bus = createRemoteBus()

    bus.send(RemoteActors.MediaManager, Message)
    expectMsg(RemoteMessage)
    verifyZeroInteractions(bus.bus)
  }

  it should "simplify sending an activation message to the associated actor" in {
    val bus = createRemoteBus()

    bus activate true
    expectMsg(RemoteRelayActor.Activate(true))
  }

  it should "support waiting for a response and ignore unhandled messages" in {
    val bus = expectListenerRegistration(createRemoteBus())
    val responseFunc: Receive = {
      case "Ping" => // not relevant, just ignore test message
    }

    bus.ask(RemoteMessage.target, Message)(responseFunc)
    expectMsg(RemoteMessage)
    val listener = fetchListener(bus)
    listener isDefinedAt Message shouldBe false
    verifyNoMoreInteractions(bus.bus)
  }

  it should "handle a response message correctly" in {
    val bus = expectListenerRegistration(createRemoteBus())
    val PingMsg = "Ping"
    val PongMsg = "Pong"
    val responseFunc: Receive = {
      case PingMsg =>
        testActor ! PongMsg
    }

    bus.ask(RemoteMessage.target, Message)(responseFunc)
    expectMsg(RemoteMessage)
    val listener = fetchListener(bus)
    listener isDefinedAt PingMsg shouldBe true
    listener(PingMsg)
    expectMsg(PongMsg)
    verify(bus.bus).removeListener(ListenerID)
  }
}
