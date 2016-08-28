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

package de.oliver_heger.linedj.client.mediaifc.remote

import akka.actor.ActorRef
import de.oliver_heger.linedj.client.comm.{ActorFactory, MessageBus}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''RemoteMessageBusFactory''.
  */
class RemoteMessageBusFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  "A RemoteMessageBusFactory" should "create a remote message bus" in {
    val actorFactory = mock[ActorFactory]
    val bus = mock[MessageBus]
    val actor = mock[ActorRef]
    when(actorFactory.createActor(ManagementActor(bus), "RemoteManagementActor"))
      .thenReturn(actor)
    val factory = new RemoteMessageBusFactory

    val remoteBus = factory.createRemoteMessageBus(actorFactory, bus)
    remoteBus.bus should be(bus)
    remoteBus.relayActor should be(actor)
  }
}
