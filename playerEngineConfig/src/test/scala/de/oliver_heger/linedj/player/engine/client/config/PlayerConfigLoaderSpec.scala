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

package de.oliver_heger.linedj.player.engine.client.config

import akka.actor.ActorRef
import de.oliver_heger.linedj.player.engine.ActorCreator
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.duration._

class PlayerConfigLoaderSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "PlayerConfigLoader" should "read the properties from a configuration" in {
    val Prefix = "player.config"
    val config = new HierarchicalConfiguration

    def set(key: String, value: Any): Unit =
      config.addProperty(s"$Prefix.$key", value)

    set(PlayerConfigLoader.PropBufferFileSize, 11111)
    set(PlayerConfigLoader.PropBufferChunkSize, 7777)
    set(PlayerConfigLoader.PropMemoryBufferSize, 161661)
    set(PlayerConfigLoader.PropPlaybackContextLimit, 9999)
    set(PlayerConfigLoader.PropBufferTempPath, "/tmp")
    set(PlayerConfigLoader.PropBufferFileExtension, ".buf")
    set(PlayerConfigLoader.PropBufferFilePrefix, "audio")
    set(PlayerConfigLoader.PropBufferTempPathParts, "foo")
    set(PlayerConfigLoader.PropBufferTempPathParts, "bar")
    set(PlayerConfigLoader.PropDownloadInProgressNotificationDelay, 10)
    set(PlayerConfigLoader.PropDownloadInProgressNotificationDelay + "[@unit]", "minutes")
    set(PlayerConfigLoader.PropDownloadInProgressNotificationInterval, 59)
    set(PlayerConfigLoader.PropDownloadInProgressNotificationInterval + "[@unit]", "seconds")
    set(PlayerConfigLoader.PropTimeProgressThreshold, 500)
    set(PlayerConfigLoader.PropTimeProgressThreshold + "[@unit]", "milliseconds")
    set(PlayerConfigLoader.PropBlockingDispatcherName, "io-dispatcher")

    val playerConfig = PlayerConfigLoader.loadPlayerConfig(config, Prefix, null, null)

    playerConfig.bufferFileSize should be(11111)
    playerConfig.bufferChunkSize should be(7777)
    playerConfig.inMemoryBufferSize should be(161661)
    playerConfig.playbackContextLimit should be(9999)
    playerConfig.bufferTempPath should be(Some(Paths.get("/tmp")))
    playerConfig.bufferFileExtension should be(".buf")
    playerConfig.bufferFilePrefix should be("audio")
    playerConfig.bufferTempPathParts should contain theSameElementsInOrderAs List("foo", "bar")
    playerConfig.downloadInProgressNotificationDelay should be(10.minutes)
    playerConfig.downloadInProgressNotificationInterval should be(59.seconds)
    playerConfig.timeProgressThreshold should be(500.millis)
    playerConfig.blockingDispatcherName should be(Some("io-dispatcher"))
  }

  it should "create a configuration with default values" in {
    val playerConfig = PlayerConfigLoader.loadPlayerConfig(new HierarchicalConfiguration, "test", null, null)

    playerConfig.bufferFileSize should be(PlayerConfigLoader.DefaultBufferFileSize)
    playerConfig.bufferChunkSize should be(PlayerConfigLoader.DefaultBufferChunkSize)
    playerConfig.inMemoryBufferSize should be(PlayerConfigLoader.DefaultMemoryBufferSize)
    playerConfig.playbackContextLimit should be(PlayerConfigLoader.DefaultPlaybackContextLimit)
    playerConfig.bufferTempPath should be(None)
    playerConfig.bufferFileExtension should be(PlayerConfigLoader.DefaultBufferFileExtension)
    playerConfig.bufferFilePrefix should be(PlayerConfigLoader.DefaultBufferFilePrefix)
    playerConfig.bufferTempPathParts should be(PlayerConfigLoader.DefaultBufferTempPathParts)
    playerConfig.downloadInProgressNotificationDelay should be(PlayerConfigLoader
      .DefaultDownloadInProgressNotificationDelay)
    playerConfig.downloadInProgressNotificationInterval should be(PlayerConfigLoader
      .DefaultDownloadInProgressNotificationInterval)
    playerConfig.timeProgressThreshold should be(PlayerConfigLoader.DefaultTimeProgressThreshold)
  }

  it should "handle a key prefix with a trailing dot" in {
    val Prefix = "player.dot.config."
    val config = new HierarchicalConfiguration
    config.addProperty(Prefix + PlayerConfigLoader.PropMemoryBufferSize, 8000)

    val playerConfig = PlayerConfigLoader.loadPlayerConfig(config, Prefix, null, null)

    playerConfig.inMemoryBufferSize should be(8000)
  }

  it should "add the media manager actor to the configuration" in {
    val manager = mock[ActorRef]

    val playerConfig = PlayerConfigLoader.loadPlayerConfig(new HierarchicalConfiguration, "", manager, null)

    playerConfig.mediaManagerActor should be(manager)
  }

  it should "add the actor creator to the configuration" in {
    val creator = mock[ActorCreator]

    val playerConfig = PlayerConfigLoader.loadPlayerConfig(new HierarchicalConfiguration, "", null, creator)

    playerConfig.actorCreator should be(creator)
  }

  it should "provide a default player configuration" in {
    val playerConfig = PlayerConfigLoader.DefaultPlayerConfig

    playerConfig.bufferFileSize should be(PlayerConfigLoader.DefaultBufferFileSize)
    playerConfig.bufferChunkSize should be(PlayerConfigLoader.DefaultBufferChunkSize)
    playerConfig.inMemoryBufferSize should be(PlayerConfigLoader.DefaultMemoryBufferSize)
    playerConfig.playbackContextLimit should be(PlayerConfigLoader.DefaultPlaybackContextLimit)
    playerConfig.bufferTempPath should be(None)
    playerConfig.bufferFileExtension should be(PlayerConfigLoader.DefaultBufferFileExtension)
    playerConfig.bufferFilePrefix should be(PlayerConfigLoader.DefaultBufferFilePrefix)
    playerConfig.bufferTempPathParts should be(PlayerConfigLoader.DefaultBufferTempPathParts)
    playerConfig.downloadInProgressNotificationDelay should be(PlayerConfigLoader
      .DefaultDownloadInProgressNotificationDelay)
    playerConfig.downloadInProgressNotificationInterval should be(PlayerConfigLoader
      .DefaultDownloadInProgressNotificationInterval)
    playerConfig.timeProgressThreshold should be(PlayerConfigLoader.DefaultTimeProgressThreshold)
  }

  it should "create a default configuration with dynamic values" in {
    val manager = mock[ActorRef]
    val creator = mock[ActorCreator]

    val playerConfig = PlayerConfigLoader.defaultConfig(manager, creator)

    playerConfig.bufferFileSize should be(PlayerConfigLoader.DefaultBufferFileSize)
    playerConfig.bufferChunkSize should be(PlayerConfigLoader.DefaultBufferChunkSize)
    playerConfig.inMemoryBufferSize should be(PlayerConfigLoader.DefaultMemoryBufferSize)
    playerConfig.playbackContextLimit should be(PlayerConfigLoader.DefaultPlaybackContextLimit)
    playerConfig.bufferTempPath should be(None)
    playerConfig.bufferFileExtension should be(PlayerConfigLoader.DefaultBufferFileExtension)
    playerConfig.bufferFilePrefix should be(PlayerConfigLoader.DefaultBufferFilePrefix)
    playerConfig.bufferTempPathParts should be(PlayerConfigLoader.DefaultBufferTempPathParts)
    playerConfig.downloadInProgressNotificationDelay should be(PlayerConfigLoader
      .DefaultDownloadInProgressNotificationDelay)
    playerConfig.downloadInProgressNotificationInterval should be(PlayerConfigLoader
      .DefaultDownloadInProgressNotificationInterval)
    playerConfig.timeProgressThreshold should be(PlayerConfigLoader.DefaultTimeProgressThreshold)
    playerConfig.mediaManagerActor should be(manager)
    playerConfig.actorCreator should be(creator)
  }
}
