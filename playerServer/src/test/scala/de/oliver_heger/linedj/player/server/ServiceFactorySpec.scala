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

package de.oliver_heger.linedj.player.server

import akka.Done
import akka.actor.{ActorSystem, Terminated}
import akka.http.scaladsl.Http.ServerBinding
import akka.testkit.TestKit
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.mp3.Mp3PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.utils.ActorManagement
import org.apache.commons.configuration.HierarchicalConfiguration
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

/**
  * Test class for [[ServiceFactory]].
  */
class ServiceFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ServiceFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "createRadioPlayer" should "correctly create and initialize a radio player" in {
    val currentSource = ServerConfigTestHelper.TestRadioSource("current")
    val otherSource = ServerConfigTestHelper.TestRadioSource("anotherSource")
    val currentConfig = new HierarchicalConfiguration
    currentConfig.addProperty(PlayerServerConfig.PropCurrentSource, currentSource.name)
    val config = ServerConfigTestHelper.defaultServerConfig(mock, List(otherSource, currentSource))
      .copy(optCurrentConfig = Some(currentConfig))

    val radioPlayerFactory = mock[RadioPlayerFactory]
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayerFactory.createRadioPlayer(config)(system)).thenReturn(Future.successful(radioPlayer))

    val factory = new ServiceFactory(radioPlayerFactory = radioPlayerFactory)
    factory.createRadioPlayer(config) map { player =>
      verify(player).addPlaybackContextFactory(any[Mp3PlaybackContextFactory]())
      verify(player).initRadioSourceConfig(config.sourceConfig)
      verify(player).initMetadataConfig(config.metadataConfig)
      verify(player).switchToRadioSource(currentSource.toRadioSource)
      verify(player).startPlayback()
      player should be(radioPlayer)
    }
  }

  it should "skip starting playback if no radio sources are available" in {
    val config = ServerConfigTestHelper.defaultServerConfig(mock)
    val radioPlayerFactory = mock[RadioPlayerFactory]
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayerFactory.createRadioPlayer(config)(system)).thenReturn(Future.successful(radioPlayer))

    val factory = new ServiceFactory(radioPlayerFactory = radioPlayerFactory)
    factory.createRadioPlayer(config) map { player =>
      verify(player, never()).switchToRadioSource(any())
      verify(player, never()).startPlayback()
      player should be(radioPlayer)
    }
  }

  "enableGracefulShutdown" should "call the shutdown when all conditions are met" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    when(mockSystem.terminate()).thenReturn(Future.successful(mock[Terminated]))
    val config = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))
    val startupData = ServiceFactory.ServerStartupData(mockBinding, config)

    val factory = new ServiceFactory
    val futTerminate = factory.enableGracefulShutdown(Future.successful(startupData),
      Future.successful(Done), mockManagement)(mockSystem)
    futTerminate map { t =>
      verify(mockBinding).addToCoordinatedShutdown(5.seconds)(mockSystem)
      verify(mockManagement).stopActors()
      verify(mockSystem).terminate()
      t shouldBe empty
    }
  }

  it should "return a Future with the shutdown command" in {
    val ShutdownCommand = "run me on shutdown"
    val config = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))
      .copy(optShutdownCommand = Some(ShutdownCommand))
    val startupData = ServiceFactory.ServerStartupData(mock, config)
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    when(mockSystem.terminate()).thenReturn(Future.successful(mock[Terminated]))

    val factory = new ServiceFactory
    val futTerminate = factory.enableGracefulShutdown(Future.successful(startupData),
      Future.successful(Done), mockManagement)(mockSystem)

    futTerminate map { optCommand =>
      optCommand should be(Some(ShutdownCommand))
    }
  }

  it should "not shutdown before the server has been fully started" in {
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseStartup = Promise[ServiceFactory.ServerStartupData]()

    val factory = new ServiceFactory
    factory.enableGracefulShutdown(promiseStartup.future, Future.successful(Done), mockManagement)(mockSystem)

    Future {
      verify(mockManagement, never()).stopActors()
      verify(mockSystem, never()).terminate()
      1 should be(1) // an assertion is needed
    }
  }

  it should "not shutdown before the shutdown future has completed" in {
    val mockBinding = mock[ServerBinding]
    val config = ServerConfigTestHelper.defaultServerConfig(ServerConfigTestHelper.actorCreator(system))
    val startupData = ServiceFactory.ServerStartupData(mockBinding, config)
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseShutdown = Promise[Done]()

    val factory = new ServiceFactory
    factory.enableGracefulShutdown(Future.successful(startupData), promiseShutdown.future, mockManagement)(mockSystem)

    Future {
      verify(mockManagement, never()).stopActors()
      verify(mockSystem, never()).terminate()
      1 should be(1)
    }
  }

  it should "shutdown the actor system even if the startup future failed" in {
    val mockSystem = mock[ActorSystem]
    when(mockSystem.terminate()).thenReturn(Future.successful(mock[Terminated]))
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val mockManagement = mock[ActorManagement]
    val promiseStartup = Promise[ServiceFactory.ServerStartupData]()

    val factory = new ServiceFactory
    val futureTerminated = factory.enableGracefulShutdown(promiseStartup.future, Future.successful(Done),
      mockManagement)(mockSystem)

    promiseStartup.failure(new IllegalStateException("Test exception"))
    futureTerminated map { t =>
      verify(mockManagement).stopActors()
      verify(mockSystem).terminate()
      t should not be null
    }
  }
