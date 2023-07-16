/*
 * Copyright 2015-2023 The Developers Team.
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
import de.oliver_heger.linedj.platform.app.support.ActorManagementComponent
import de.oliver_heger.linedj.platform.app.{ClientApplication, ClientApplicationContext}
import de.oliver_heger.linedj.player.engine.client.config.ManagingActorCreator
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

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
    * Creates a stub [[ActorManagementComponent]] object. The object can also
    * be used to stop all actors that have been created during a test.
    *
    * @return the stub
    */
  private def createActorManagement(): ActorManagementComponent = {
    val context = mock[ClientApplicationContext]
    when(context.actorFactory).thenReturn(new ActorFactory(system))

    new ActorManagementComponent {
      override def initClientContext(context: ClientApplicationContext): Unit = {}

      override def clientApplicationContext: ClientApplicationContext = context
    }
  }

  "A RadioPlayerFactory" should "use meaningful configuration settings" in {
    implicit val ec: ExecutionContext = system.dispatcher
    val factory = new RadioPlayerFactory
    val management = createActorManagement()
    val player = futureResult(factory createRadioPlayer management)

    val playerConfig = player.config.playerConfig
    playerConfig.inMemoryBufferSize should be(65536)
    playerConfig.bufferChunkSize should be(4096)
    playerConfig.playbackContextLimit should be(8192)
    playerConfig.mediaManagerActor should be(null)
    playerConfig.timeProgressThreshold should be(100.millis)
    playerConfig.blockingDispatcherName.get should be(ClientApplication.BlockingDispatcherName)
    playerConfig.actorCreator match {
      case c: ManagingActorCreator => c.actorManagement should be(management)
      case o => fail("Unexpected actor creator: " + o)
    }
  }
}
