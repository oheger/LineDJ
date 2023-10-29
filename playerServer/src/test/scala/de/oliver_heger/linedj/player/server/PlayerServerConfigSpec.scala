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

import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.client.config.RadioPlayerConfigLoader
import org.apache.commons.configuration.{CombinedConfiguration, HierarchicalConfiguration, PropertiesConfiguration}
import org.apache.pekko.actor.ActorRef
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
    config.optShutdownCommand should be(Some("sudo shutdown -h now"))
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
    config.optShutdownCommand shouldBe empty
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

  it should "return the UI path prefix" in {
    val config = PlayerServerConfig("test-server-config.xml", null, null)

    config.uiPathPrefix should be("ui")
  }

  it should "return the UI path prefix if the UI path does not start with a slash" in {
    val config = PlayerServerConfig("test-server-config.xml", null, null)
      .copy(uiPath = "ui/without/leading/slash.html")

    config.uiPathPrefix should be("ui")
  }

  it should "return None as the name of the current source if no current config is defined" in {
    val config = PlayerServerConfig("test-server-config-empty.xml", null, null)

    config.currentSourceName shouldBe empty
  }

  it should "return the name of the current radio source if it is defined" in {
    val config = PlayerServerConfig("test-server-config.xml", null, null)

    config.currentSourceName should be(Some("HR 1"))
  }

  it should "return None as the name of the current source if no current source property is defined" in {
    val config = ServerConfigTestHelper.defaultServerConfig(null)
      .copy(optCurrentConfig = Some(new HierarchicalConfiguration))

    config.currentSourceName shouldBe empty
  }

  it should "return None for the current radio source if the name cannot be resolved" in {
    val currentConfig = new HierarchicalConfiguration
    currentConfig.addProperty(PlayerServerConfig.PropCurrentSource, "an unknown radio source")
    val config = PlayerServerConfig("test-server-config.xml", null, null)
      .copy(optCurrentConfig = Some(currentConfig))

    config.currentSource shouldBe empty
  }

  it should "return the current radio source if it is defined" in {
    val config = PlayerServerConfig("test-server-config.xml", null, null)
    val expectedSource = config.sourceConfig.sources.head

    config.currentSource should be(Some(expectedSource))
  }

  it should "return the current radio source as initial source if it is defined" in {
    val config = PlayerServerConfig("test-server-config.xml", null, null)
    val expectedSource = config.sourceConfig.sources.head

    config.initialSource should be(Some(expectedSource))
  }

  it should "return the first radio source as initial source if no current source is defined" in {
    val currentConfig = new HierarchicalConfiguration
    currentConfig.addProperty(PlayerServerConfig.PropCurrentSource, "an unknown radio source")
    val config = PlayerServerConfig("test-server-config.xml", null, null)
      .copy(optCurrentConfig = Some(currentConfig))
    val expectedSource = config.sourceConfig.sources.head

    config.initialSource should be(Some(expectedSource))
  }

  it should "return None for the initial source if no sources are defined" in {
    val config = PlayerServerConfig("test-server-config-empty.xml", null, null)

    config.initialSource shouldBe empty
  }

  it should "set the auto-save flag in the current configuration if it is a file configuration" in {
    val combinedConfig = new CombinedConfiguration
    val currentConfig = new PropertiesConfiguration
    combinedConfig.addConfiguration(currentConfig, PlayerServerConfig.CurrentSourceConfigName)

    val playerConfig = PlayerServerConfig(combinedConfig, null, null)

    currentConfig.isAutoSave shouldBe true
  }
