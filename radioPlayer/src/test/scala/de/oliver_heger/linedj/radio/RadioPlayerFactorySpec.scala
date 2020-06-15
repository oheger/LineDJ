/*
 * Copyright 2015-2020 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.radio

import akka.actor.{Actor, ActorRef, Props}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''RadioPlayerFactory''.
  */
class RadioPlayerFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a mock ActorManagement object.
    * @return the mock
    */
  private def createActorManagement(): ActorManagement = {
    val management = mock[ActorManagement]
    when(management.createAndRegisterActor(any(classOf[Props]), anyString()))
      .thenAnswer((_: InvocationOnMock) => mock[ActorRef])
    management
  }

  "A RadioPlayerFactory" should "use meaningful configuration settings" in {
    val factory = new RadioPlayerFactory

    val player = factory createRadioPlayer createActorManagement()
    player.config.inMemoryBufferSize should be(65536)
    player.config.bufferChunkSize should be(4096)
    player.config.playbackContextLimit should be(8192)
    player.config.mediaManagerActor should be(null)
    player.config.blockingDispatcherName.get should be(ClientApplication.BlockingDispatcherName)
  }

  it should "specify a correct actor creation function" in {
    val management = createActorManagement()
    val factory = new RadioPlayerFactory

    val player = factory createRadioPlayer management
    val mockActor = mock[ActorRef]
    val props = Props(new Actor {
      override def receive: Receive = Actor.emptyBehavior
    })
    val name = "someActor"
    when(management.createAndRegisterActor(props, name)).thenReturn(mockActor)
    player.config.actorCreator(props, name) should be(mockActor)
    verify(management).createAndRegisterActor(props, name)
  }
}
