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

package de.oliver_heger.linedj.platform.audio.impl

import de.oliver_heger.linedj.player.engine.ActorCreator
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.apache.pekko.actor.ActorRef
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.duration._

object PlayerConfigFactorySpec:
  /** Test value of this config property. */
  private val InMemoryBufSize = 123456

  /** Test value of this config property. */
  private val PlaybackCtxLimit = 88888

  /** Test value of this config property. */
  private val BufferFileSize = 456789

  /** Test value of this config property. */
  private val BufferChunkSize = 10000

  /** Test value of this config property. */
  private val BufferFilePrefix = "Buffer-"

  /** Test value of this config property. */
  private val BufferFileExt = ".buf"

  /** Test value of this config property. */
  private val BufferTempPath = ".bufferPath"

  /** Test value of this config property. */
  private val BufferPathParts = List("foo", "bar")

  /** Test value of this config property. */
  private val DownloadNotificationDelay = 10.minutes

  /** Test value of this config property. */
  private val DownloadNotificationInterval = 15.minutes

  /** Test value of this config property. */
  private val BlockingDispatcherName = "myBlockingDispatcher"

  /** The prefix for configuration settings. */
  private val Prefix = "player.config"

  /**
    * Creates a configuration with test settings.
    *
    * @return the configuration
    */
  private def createConfiguration(): Configuration =
    val p = Prefix + '.'
    val c = new PropertiesConfiguration
    import PlayerConfigFactory._

    c.addProperty(p + PropInMemoryBufferSize, InMemoryBufSize)
    c.addProperty(p + PropPlaybackContextLimit, PlaybackCtxLimit)
    c.addProperty(p + PropBufferFileSize, BufferFileSize)
    c.addProperty(p + PropBufferChunkSize, BufferChunkSize)
    c.addProperty(p + PropBufferFilePrefix, BufferFilePrefix)
    c.addProperty(p + PropBufferFileExt, BufferFileExt)
    c.addProperty(p + PropBufferTempPath, BufferTempPath)
    c.addProperty(p + PropBufferTempPathParts, BufferPathParts.toArray)
    c.addProperty(p + PropDownloadProgressNotificationDelay,
      DownloadNotificationDelay.toSeconds)
    c.addProperty(p + PropDownloadProgressNotificationInterval,
      DownloadNotificationInterval.toSeconds)
    c.addProperty(p + PropBlockingDispatcherName, BlockingDispatcherName)
    c

/**
  * Test class for ''PlayerConfigFactory''.
  */
class PlayerConfigFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import PlayerConfigFactorySpec._

  "A PlayerConfigFactory" should "read configuration settings" in:
    val c = createConfiguration()
    val factory = new PlayerConfigFactory

    val conf = factory.createPlayerConfig(c, Prefix, mock[ActorRef], mock[ActorCreator])
    conf.inMemoryBufferSize should be(InMemoryBufSize)
    conf.playbackContextLimit should be(PlaybackCtxLimit)
    conf.bufferFileSize should be(BufferFileSize)
    conf.bufferChunkSize should be(BufferChunkSize)
    conf.bufferFilePrefix should be(BufferFilePrefix)
    conf.bufferFileExtension should be(BufferFileExt)
    conf.bufferTempPath should be(Some(Paths.get(BufferTempPath)))
    conf.bufferTempPathParts should be(BufferPathParts)
    conf.downloadInProgressNotificationDelay should be(DownloadNotificationDelay)
    conf.downloadInProgressNotificationInterval should be(DownloadNotificationInterval)
    conf.blockingDispatcherName should be(Some(BlockingDispatcherName))

  it should "use meaningful default values" in:
    import PlayerConfigFactory._
    val factory = new PlayerConfigFactory

    val conf = factory.createPlayerConfig(new PropertiesConfiguration, Prefix,
      mock[ActorRef], mock[ActorCreator])
    conf.inMemoryBufferSize should be(DefInMemoryBufferSize)
    conf.playbackContextLimit should be(DefPlaybackContextLimit)
    conf.bufferFileSize should be(DefBufferFileSize)
    conf.bufferChunkSize should be(DefBufferChunkSize)
    conf.bufferFilePrefix should be(DefBufferFilePrefix)
    conf.bufferFileExtension should be(DefBufferFileExt)
    conf.bufferTempPath shouldBe empty
    conf.bufferTempPathParts shouldBe empty
    conf.downloadInProgressNotificationDelay should be(DefDownloadProgressNotificationDelay)
    conf.downloadInProgressNotificationInterval should be(DefDownloadProgressNotificationInterval)
    conf.blockingDispatcherName shouldBe empty
    conf.timeProgressThreshold should be(100.millis)

  it should "store the management actor and the actor creator" in:
    val managementActor = mock[ActorRef]
    val actorCreator = mock[ActorCreator]
    val configuration = createConfiguration()
    val factory = new PlayerConfigFactory

    val conf = factory.createPlayerConfig(configuration, Prefix, managementActor, actorCreator)
    conf.mediaManagerActor should be(managementActor)
    conf.actorCreator should be(actorCreator)

  it should "handle a prefix that ends on a separator" in:
    val configuration = createConfiguration()
    val factory = new PlayerConfigFactory

    val conf = factory.createPlayerConfig(configuration, Prefix + '.',
      mock[ActorRef], mock[ActorCreator])
    conf.inMemoryBufferSize should be(InMemoryBufSize)
