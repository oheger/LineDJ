/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.impl

import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.app.support.ActorManagementComponent
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.{ManagingActorCreator, PlayerConfigLoader}
import de.oliver_heger.linedj.utils.ActorFactory
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.ArgumentMatchers.{any, eq as eqArg}
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicReference

/**
  * Test class for ''AudioPlayerFactory''.
  */
class AudioPlayerFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper:
  def this() = this(ActorSystem("AudioPlayerFactorySpec"))

  import system.dispatcher

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  "An AudioPlayerFactory" should "create a correct default config factory" in:
    val factory = new AudioPlayerFactory

    factory.playerConfigFactory should not be null

  it should "create a correct audio player" in:
    val configFactory = mock[PlayerConfigFactory]
    val clientAppContext = mock[ClientApplicationContext]
    val mediaManager = TestProbe()
    val Prefix = "audio.platform.config"
    val BufSize = 8888
    val appConfig = new PropertiesConfiguration
    val refCreator = new AtomicReference[ActorCreator]
    val management = new ActorManagementComponent:
      override def initClientContext(context: ClientApplicationContext): Unit = {}

      override val clientApplicationContext: ClientApplicationContext = clientAppContext

    when(clientAppContext.actorFactory).thenReturn(new ActorFactory(system))
    when(configFactory.createPlayerConfig(eqArg(appConfig), eqArg(Prefix),
      eqArg(mediaManager.ref), any())).thenAnswer((invocation: InvocationOnMock) => {
      val creator = invocation.getArguments()(3).asInstanceOf[ActorCreator]
      refCreator.set(creator)
      PlayerConfigLoader.DefaultPlayerConfig.copy(inMemoryBufferSize = BufSize,
        mediaManagerActor = mediaManager.ref,
        actorCreator = creator)
    })
    val factory = new AudioPlayerFactory(configFactory)

    val player = futureResult(factory.createAudioPlayer(appConfig, Prefix, mediaManager.ref, management))

    player should not be null
    refCreator.get() match
      case c: ManagingActorCreator => c.actorManagement should be(management)
      case o => fail("Unexpected actor creator: " + o)
