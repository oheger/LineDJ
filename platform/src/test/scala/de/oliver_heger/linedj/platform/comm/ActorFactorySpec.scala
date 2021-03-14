/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.platform.comm

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
 * Test class for ''ActorFactory''.
 */
class ActorFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "An ActorFactory" should "allow creating a new actor" in {
    val system = mock[ActorSystem]
    val ref = mock[ActorRef]
    val props = Props[DummyActor]
    val Name = "MyTestActor"
    when(system.actorOf(props, Name)).thenReturn(ref)

    val factory = new ActorFactory(system)
    factory.createActor(props, Name) should be(ref)
  }
}

class DummyActor extends Actor {
  override def receive: Receive = Actor.emptyBehavior
}
