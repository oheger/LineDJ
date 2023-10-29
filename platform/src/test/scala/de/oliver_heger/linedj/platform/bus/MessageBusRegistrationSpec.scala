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

package de.oliver_heger.linedj.platform.bus

import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import org.apache.pekko.actor.Actor
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''MessageBusRegistration''.
 */
class MessageBusRegistrationSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
   * Creates a mock for a message bus listener together with a mock for the
   * message handling function.
   * @return the mocks for the listener and the message function
   */
  private def createListenerMock(): (MessageBusListener, Actor.Receive) = {
    val listener = mock[MessageBusListener]
    val rec = mock[Actor.Receive]
    when(listener.receive).thenReturn(rec)
    (listener, rec)
  }

  /**
   * Creates a map with some test bus listeners and their message handling
   * functions.
   * @return the map with test listeners
   */
  private def createListeners(): Map[MessageBusListener, Actor.Receive] = {
    val listeners = 1 to 5 map (_ => createListenerMock())
    Map(listeners: _*)
  }

  "A MessageBusRegistration" should "register all message bus listeners" in {
    import scala.jdk.CollectionConverters._
    val bus = mock[MessageBus]
    val listenerMap = createListeners()
    val registration = new MessageBusRegistration(listenerMap.keySet.asJava)

    registration setMessageBus bus
    listenerMap.values foreach verify(bus).registerListener
  }

  it should "remove registrations in a shutdown method" in {
    import scala.jdk.CollectionConverters._
    val bus = mock[MessageBus]
    val listenerMap = createListeners()
    listenerMap.values.zipWithIndex.foreach { t =>
      when(bus.registerListener(t._1)).thenReturn(t._2)
    }
    val registration = new MessageBusRegistration(listenerMap.keySet.asJava)
    registration setMessageBus bus

    registration.removeRegistrations()
    (0 until listenerMap.size) foreach { i =>
      verify(bus).removeListener(i)
    }
  }
}
