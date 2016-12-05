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

import akka.actor.Actor
import akka.testkit.TestActorRef
import de.oliver_heger.linedj.platform.comm.MessageBus
import org.mockito.Matchers.any
import org.mockito.Mockito.{verify, when}
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.mock.MockitoSugar

object ShutdownActorTestHelper {
  /** Registration ID for the message bus. */
  private val RegistrationID = 20161203
}

/**
  * A helper trait used by test classes for shutdown actors.
  *
  * @tparam A the actor type to be tested
  */
trait ShutdownActorTestHelper[A <: Actor] extends MockitoSugar {

  import ShutdownActorTestHelper._

  /** A mock for the message bus. */
  val bus: MessageBus = createMessageBus()

  /** The actor to be tested. */
  val actor: TestActorRef[A]

  /**
    * Sends a message to the test actor.
    *
    * @param msg the message to be sent
    * @return this test helper
    */
  def receive(msg: Any): ShutdownActorTestHelper[A] = {
    actor receive msg
    this
  }

  /**
    * Checks whether a listener has been registered at the message bus and
    * was sent a message. The listener is then invoked with this message.
    *
    * @return this test helper
    */
  def expectAndInvokeBusListener(): ShutdownActorTestHelper[A] = {
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
  def expectRemoveBusListener(): ShutdownActorTestHelper[A] = {
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
  def expectPublish(msg: Any): ShutdownActorTestHelper[A] = {
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
