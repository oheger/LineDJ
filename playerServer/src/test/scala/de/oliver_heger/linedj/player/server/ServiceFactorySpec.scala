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
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.IntervalQuery
import de.oliver_heger.linedj.player.engine.mp3.Mp3PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.ServerConfigTestHelper.futureResult
import de.oliver_heger.linedj.utils.ActorManagement
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.{AnyFlatSpec, AnyFlatSpecLike}
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.net.ServerSocket
import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

/**
  * Test class for [[ServiceFactory]].
  */
class ServiceFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ServiceFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "createRadioPlayer" should "correctly create and initialize a radio player" in {
    val radioSource = new RadioSource("https://radio.example.org/test.mp3")
    val sourceConfig = new RadioSourceConfig:
      override val namedSources: Seq[(String, RadioSource)] =
        Seq("test" -> radioSource)
      override def exclusions(source: RadioSource): Seq[IntervalQuery] = Seq.empty
      override def ranking(source: RadioSource): Int = 0

    val config = ServerConfigTestHelper.defaultServerConfig(mock)
      .copy(sourceConfig = sourceConfig)

    val radioPlayerFactory = mock[RadioPlayerFactory]
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayerFactory.createRadioPlayer(config)(system)).thenReturn(Future.successful(radioPlayer))

    val factory = new ServiceFactory(radioPlayerFactory = radioPlayerFactory)
    futureResult(factory.createRadioPlayer(config)) should be(radioPlayer)

    verify(radioPlayer).addPlaybackContextFactory(any[Mp3PlaybackContextFactory]())
    verify(radioPlayer).initRadioSourceConfig(config.sourceConfig)
    verify(radioPlayer).initMetadataConfig(config.metadataConfig)
    verify(radioPlayer).switchToRadioSource(radioSource)
    verify(radioPlayer).startPlayback()
  }

  it should "skip starting playback if no radio sources are available" in {
    val config = ServerConfigTestHelper.defaultServerConfig(mock)
    val radioPlayerFactory = mock[RadioPlayerFactory]
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayerFactory.createRadioPlayer(config)(system)).thenReturn(Future.successful(radioPlayer))

    val factory = new ServiceFactory(radioPlayerFactory = radioPlayerFactory)
    futureResult(factory.createRadioPlayer(config)) should be(radioPlayer)

    verify(radioPlayer, never()).switchToRadioSource(any())
    verify(radioPlayer, never()).startPlayback()
  }

  "enableGracefulShutdown" should "call the shutdown when all conditions are met" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    when(mockSystem.terminate()).thenReturn(Future.successful(Done))

    val factory = new ServiceFactory
    val futTerminate = factory.enableGracefulShutdown(Future.successful(mockBinding),
      Future.successful(Done), mockManagement)(mockSystem)
    futureResult(futTerminate)
    verify(mockBinding).addToCoordinatedShutdown(5.seconds)(mockSystem)
    verify(mockManagement).stopActors()
    verify(mockSystem).terminate()
  }

  it should "not shutdown before the server has been fully started" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseBinding = Promise[ServerBinding]()

    val factory = new ServiceFactory
    factory.enableGracefulShutdown(promiseBinding.future, Future.successful(Done), mockManagement)(mockSystem)

    verify(mockManagement, never()).stopActors()
    verify(mockSystem, never()).terminate()
  }

  it should "not shutdown before the shutdown future has completed" in {
    val mockBinding = mock[ServerBinding]
    val mockSystem = mock[ActorSystem]
    val mockManagement = mock[ActorManagement]
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val promiseShutdown = Promise[Done]()

    val factory = new ServiceFactory
    factory.enableGracefulShutdown(Future.successful(mockBinding), promiseShutdown.future, mockManagement)(mockSystem)

    verify(mockManagement, never()).stopActors()
    verify(mockSystem, never()).terminate()
  }

  it should "shutdown the actor system even if the binding future failed" in {
    val mockSystem = mock[ActorSystem]
    when(mockSystem.terminate()).thenReturn(Future.successful(Terminated))
    when(mockSystem.dispatcher).thenReturn(system.dispatcher)
    val mockManagement = mock[ActorManagement]
    val promiseBinding = Promise[ServerBinding]()

    val factory = new ServiceFactory
    val futureTerminated = factory.enableGracefulShutdown(promiseBinding.future, Future.successful(Done),
      mockManagement)(mockSystem)

    promiseBinding.failure(new IllegalStateException("Test exception"))
    futureResult(futureTerminated)
    verify(mockManagement).stopActors()
    verify(mockSystem).terminate()
  }
