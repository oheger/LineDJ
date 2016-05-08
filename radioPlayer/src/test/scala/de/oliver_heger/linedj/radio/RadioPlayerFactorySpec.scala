/*
 * Copyright 2015-2016 The Developers Team.
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

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.client.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.client.remoting.ActorFactory
import de.oliver_heger.linedj.io.FileReaderActor
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''RadioPlayerFactory''.
  */
class RadioPlayerFactorySpec extends FlatSpec with Matchers with MockitoSugar {
  private def createApplicationContext(): ClientApplicationContext = {
    val context = mock[ClientApplicationContext]
    val actorFactory = mock[ActorFactory]
    when(context.actorFactory).thenReturn(actorFactory)
    when(actorFactory.createActor(any(classOf[Props]), anyString())).thenAnswer(new
        Answer[ActorRef] {
      override def answer(invocation: InvocationOnMock): ActorRef =
        mock[ActorRef]
    })
    context
  }

  "A RadioPlayerFactory" should "use meaningful configuration settings" in {
    val factory = new RadioPlayerFactory

    val player = factory createRadioPlayer createApplicationContext()
    player.config.inMemoryBufferSize should be(16384)
    player.config.bufferChunkSize should be(4096)
    player.config.playbackContextLimit should be(8192)
    player.config.mediaManagerActor should be(null)
    player.config.blockingDispatcherName.get should be(ClientApplication.BlockingDispatcherName)
  }

  it should "specify a correct actor creation function" in {
    val context = createApplicationContext()
    val factory = new RadioPlayerFactory

    val player = factory createRadioPlayer context
    val mockActor = mock[ActorRef]
    val props = Props[FileReaderActor]()
    val name = "someActor"
    when(context.actorFactory.createActor(props, name)).thenReturn(mockActor)
    player.config.actorCreator(props, name) should be(mockActor)
    verify(context.actorFactory).createActor(props, name)
  }
}
