/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import java.nio.file.Paths

import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

object PlaylistHandlerConfigSpec {
  /** Path to the playlist file. */
  private val PathPlaylist = Paths get "persistedPlaylist.json"

  /** Path to the position file. */
  private val PathPosition = Paths get "position.json"

  /** The maximum size of a playlist file. */
  private val MaxFileSize = 8 * 1024

  /** The test auto save interval. */
  private val AutoSaveInterval = 90.seconds

  /** The test shutdown timeout. */
  private val ShutdownTimeout = 11.seconds

  /**
    * Creates a configuration that contains all the test settings.
    *
    * @return the initialized configuration
    */
  private def createFullConfig(): Configuration = {
    val config = new PropertiesConfiguration
    config.addProperty(PlaylistHandlerConfig.PropPlaylistPath, PathPlaylist.toString)
    config.addProperty(PlaylistHandlerConfig.PropPositionPath, PathPosition.toString)
    config.addProperty(PlaylistHandlerConfig.PropMaxFileSize, MaxFileSize)
    config.addProperty(PlaylistHandlerConfig.PropAutoSaveInterval, AutoSaveInterval.toSeconds)
    config.addProperty(PlaylistHandlerConfig.PropShutdownTimeout, ShutdownTimeout.toMillis)
    config
  }

  /**
    * Convenience function to remove a property from a configuration.
    *
    * @param c    the configuration
    * @param prop the property to be removed
    * @return the updated configuration
    */
  private def without(c: Configuration, prop: String): Configuration = {
    c clearProperty prop
    c
  }
}

/**
  * Test class for ''PlaylistHandlerConfig''.
  */
class PlaylistHandlerConfigSpec extends FlatSpec with Matchers {

  import PlaylistHandlerConfigSpec._

  "A PlaylistHandlerConfig" should "read all settings from a Configuration" in {
    val config = PlaylistHandlerConfig(createFullConfig()).get

    config.pathPlaylist should be(PathPlaylist)
    config.pathPosition should be(PathPosition)
    config.maxFileSize should be(MaxFileSize)
    config.autoSaveInterval should be(AutoSaveInterval)
    config.shutdownTimeout should be(ShutdownTimeout)
  }

  it should "detect a missing playlist path" in {
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropPlaylistPath)

    PlaylistHandlerConfig(settings).isFailure shouldBe true
  }

  it should "detect a missing position path" in {
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropPositionPath)

    PlaylistHandlerConfig(settings).isFailure shouldBe true
  }

  it should "disable auto-save if the property is not defined" in {
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropAutoSaveInterval)
    val config = PlaylistHandlerConfig(settings).get

    config.autoSaveInterval.toSeconds should be(Integer.MAX_VALUE)
  }

  it should "disable the maximum file size check if the property is not defined" in {
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropMaxFileSize)
    val config = PlaylistHandlerConfig(settings).get

    config.maxFileSize should be(Integer.MAX_VALUE)
  }

  it should "set a default value for shutdown timeout if the property is not defined" in {
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropShutdownTimeout)
    val config = PlaylistHandlerConfig(settings).get

    config.shutdownTimeout should be(PlaylistHandlerConfig.DefaultShutdownTimeout)
  }
}
