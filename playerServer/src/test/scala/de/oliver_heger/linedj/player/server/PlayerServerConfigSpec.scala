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
package de.oliver_heger.linedj.player.server

import akka.actor.ActorRef
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.client.config.RadioPlayerConfigLoader
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.duration.*

/**
  * Test class for [[PlayerServerConfig]].
  */
class PlayerServerConfigSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "PlayerServerConfig" should "read all configuration properties" in {
    val config = PlayerServerConfig("test-server-config.xml", null, null)

    config.serverPort should be(8001)
    config.lookupMulticastAddress should be("231.11.12.15")
    config.lookupPort should be(9876)
    config.lookupCommand should be("HELLO")
    config.uiContentFolder.toString should be("webapp")
    config.uiPath should be("/ui/test/welcome.html")
    config.radioPlayerConfig.playerConfig.inMemoryBufferSize should be(65536)
    config.radioPlayerConfig.playerConfig.bufferTempPath should be(Some(Paths.get("/tmp")))
    config.radioPlayerConfig.maximumEvalDelay should be(2.hours)
    config.radioPlayerConfig.streamCacheTime should be(5.seconds)
  }

  it should "set default values for unspecified properties" in {
    val config = PlayerServerConfig("test-server-config-empty.xml", null, null)

    config.serverPort should be(PlayerServerConfig.DefaultServerPort)
    config.lookupMulticastAddress should be(PlayerServerConfig.DefaultLookupMulticastAddress)
    config.lookupPort should be(PlayerServerConfig.DefaultLookupPort)
    config.lookupCommand should be(PlayerServerConfig.DefaultLookupCommand)
    config.uiContentFolder.toString should be(PlayerServerConfig.DefaultUiContentFolder)
    config.uiPath should be(PlayerServerConfig.DefaultUiPath)
    config.radioPlayerConfig.playerConfig.inMemoryBufferSize should be(PlayerConfigLoader.DefaultMemoryBufferSize)
    config.radioPlayerConfig.stalledPlaybackCheck should be(RadioPlayerConfigLoader.DefaultStalledPlaybackCheck)
    config.sourceConfig.sources shouldBe empty
    config.metadataConfig.exclusions shouldBe empty
  }

  it should "set the media manager actor" in {
    val mediaManager = mock[ActorRef]

    val config = PlayerServerConfig("test-server-config.xml", mediaManager, null)

    config.radioPlayerConfig.playerConfig.mediaManagerActor should be(mediaManager)
  }

  it should "set the actor creation" in {
    val creator = mock[ActorCreator]

    val config = PlayerServerConfig("test-server-config.xml", null, creator)

    config.radioPlayerConfig.playerConfig.actorCreator should be(creator)
  }
