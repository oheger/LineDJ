/*
 * Copyright 2015-2026 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.mp3.Mp3AudioStreamFactory
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Test class for [[ServiceFactory]].
  */
class ServiceFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("ServiceFactorySpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  "createRadioPlayer" should "correctly create and initialize a radio player" in :
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
    factory.createRadioPlayer(config) map : player =>
      verify(player).addAudioStreamFactory(any[Mp3AudioStreamFactory]())
      verify(player).initRadioSourceConfig(config.sourceConfig)
      verify(player).initMetadataConfig(config.metadataConfig)
      verify(player).switchToRadioSource(currentSource.toRadioSource)
      verify(player).startPlayback()
      player should be(radioPlayer)

  it should "skip starting playback if no radio sources are available" in :
    val config = ServerConfigTestHelper.defaultServerConfig(mock)
    val radioPlayerFactory = mock[RadioPlayerFactory]
    val radioPlayer = mock[RadioPlayer]
    when(radioPlayerFactory.createRadioPlayer(config)(system)).thenReturn(Future.successful(radioPlayer))

    val factory = new ServiceFactory(radioPlayerFactory = radioPlayerFactory)
    factory.createRadioPlayer(config) map : player =>
      verify(player, never()).switchToRadioSource(any())
      verify(player, never()).startPlayback()
      player should be(radioPlayer)

  "createServerRunner" should "create a ServerRunner instance" in :
    val factory = new ServiceFactory

    val runner = factory.createServerRunner()

    runner should not be null
