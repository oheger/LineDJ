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

package de.oliver_heger.linedj.client.comm

import akka.actor.{ActorRef, ActorSystem, Props}
import de.oliver_heger.linedj.client.mediaifc.RemoteRelayActor
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
 * Test class for ''ActorFactory''.
 */
class ActorFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  "An ActorFactory" should "allow creating a new actor" in {
    val system = mock[ActorSystem]
    val ref = mock[ActorRef]
    val props = Props(classOf[RemoteRelayActor], "remoteHost", 1111, mock[MessageBus])
    val Name = "MyTestActor"
    when(system.actorOf(props, Name)).thenReturn(ref)

    val factory = new ActorFactory(system)
    factory.createActor(props, Name) should be(ref)
  }
}
