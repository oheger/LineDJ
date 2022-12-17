/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.ActorSystem
import akka.testkit.TestKit
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.platform.audio.actors.ManagingActorCreator
import de.oliver_heger.linedj.platform.comm.ActorFactory
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext

/**
  * Test class for ''RadioPlayerFactory''.
  */
class RadioPlayerFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("RadioPlayerFactorySpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  /**
    * Creates a stub ActorManagement object. The object can also be used to
    * stop all actors that have been created during a test.
    *
    * @return the stub
    */
  private def createActorManagement(): ActorManagement = {
    val context = mock[ClientApplicationContext]
    when(context.actorFactory).thenReturn(new ActorFactory(system))

    new ActorManagement {
      override def initClientContext(context: ClientApplicationContext): Unit = {}

      override def clientApplicationContext: ClientApplicationContext = context
    }
  }

  "A RadioPlayerFactory" should "use meaningful configuration settings" in {
    implicit val ec: ExecutionContext = system.dispatcher
    val factory = new RadioPlayerFactory
    val management = createActorManagement()
    val player = futureResult(factory createRadioPlayer management)

    player.config.inMemoryBufferSize should be(65536)
    player.config.bufferChunkSize should be(4096)
    player.config.playbackContextLimit should be(8192)
    player.config.mediaManagerActor should be(null)
    player.config.blockingDispatcherName.get should be(ClientApplication.BlockingDispatcherName)
    player.config.actorCreator match {
      case c: ManagingActorCreator => c.actorManagement should be(management)
      case o => fail("Unexpected actor creator: " + o)
    }
  }
}
