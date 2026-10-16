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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.concurrent.duration.*

object PlaylistHandlerConfigSpec:
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

  /** The test minimum backoff when checking for the playlist. */
  private val CheckMinBackoff = 100.millis

  /** The test maximum backoff when checking for the playlist. */
  private val CheckMaxBackoff = 100.seconds

  /** The test factor when checking for the playlist. */
  private val CheckFactor = 1.5

  /**
    * Creates a configuration that contains all the test settings.
    *
    * @return the initialized configuration
    */
  private def createFullConfig(): Configuration =
    val config = new PropertiesConfiguration
    config.addProperty(PlaylistHandlerConfig.PropPlaylistPath, PathPlaylist.toString)
    config.addProperty(PlaylistHandlerConfig.PropPositionPath, PathPosition.toString)
    config.addProperty(PlaylistHandlerConfig.PropMaxFileSize, MaxFileSize)
    config.addProperty(PlaylistHandlerConfig.PropAutoSaveInterval, s"${AutoSaveInterval.toSeconds}sec")
    config.addProperty(PlaylistHandlerConfig.PropShutdownTimeout, s"${ShutdownTimeout.toMillis} ms")
    config.addProperty(PlaylistHandlerConfig.PropCheckPlaylistMinBackoff, s"${CheckMinBackoff.toMillis}ms")
    config.addProperty(PlaylistHandlerConfig.PropCheckPlaylistMaxBackoff, s"${CheckMaxBackoff.toSeconds} sec")
    config.addProperty(PlaylistHandlerConfig.PropCheckPlaylistFactor, CheckFactor)
    config

  /**
    * Convenience function to remove properties from a configuration.
    *
    * @param c    the configuration
    * @param props the properties to be removed
    * @return the updated configuration
    */
  private def without(c: Configuration, props: String*): Configuration =
    props.foreach(c.clearProperty)
    c
end PlaylistHandlerConfigSpec

/**
  * Test class for [[PlaylistHandlerConfig]].
  */
class PlaylistHandlerConfigSpec extends AnyFlatSpec with Matchers:

  import PlaylistHandlerConfigSpec.*

  "A PlaylistHandlerConfig" should "read all settings from a Configuration" in:
    val config = PlaylistHandlerConfig(createFullConfig()).get

    config.pathPlaylist should be(PathPlaylist)
    config.pathPosition should be(PathPosition)
    config.maxFileSize should be(MaxFileSize)
    config.autoSaveInterval should be(AutoSaveInterval)
    config.shutdownTimeout should be(ShutdownTimeout)
    config.checkPlaylistMinBackoff should be(CheckMinBackoff)
    config.checkPlaylistMaxBackoff should be(CheckMaxBackoff)
    config.checkPlaylistFactor should be(CheckFactor)

  it should "detect a missing playlist path" in:
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropPlaylistPath)

    PlaylistHandlerConfig(settings).isFailure shouldBe true

  it should "detect a missing position path" in:
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropPositionPath)

    PlaylistHandlerConfig(settings).isFailure shouldBe true

  it should "detect an invalid duration" in:
    val config = createFullConfig()
    config.setProperty(PlaylistHandlerConfig.PropCheckPlaylistMaxBackoff, "no number value")

    PlaylistHandlerConfig(config).isFailure shouldBe true

  it should "disable auto-save if the property is not defined" in:
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropAutoSaveInterval)
    val config = PlaylistHandlerConfig(settings).get

    config.autoSaveInterval.toSeconds should be(Integer.MAX_VALUE)

  it should "disable the maximum file size check if the property is not defined" in:
    val settings = without(createFullConfig(), PlaylistHandlerConfig.PropMaxFileSize)
    val config = PlaylistHandlerConfig(settings).get

    config.maxFileSize should be(Integer.MAX_VALUE)

  it should "set default values for undefined optional properties" in:
    val settings = without(
      createFullConfig(),
      PlaylistHandlerConfig.PropShutdownTimeout,
      PlaylistHandlerConfig.PropCheckPlaylistMinBackoff,
      PlaylistHandlerConfig.PropCheckPlaylistMaxBackoff,
      PlaylistHandlerConfig.PropCheckPlaylistFactor
    )
    val config = PlaylistHandlerConfig(settings).get

    config.shutdownTimeout should be(PlaylistHandlerConfig.DefaultShutdownTimeout)
    config.checkPlaylistMinBackoff should be(PlaylistHandlerConfig.DefaultCheckPlaylistMinBackoff)
    config.checkPlaylistMaxBackoff should be(PlaylistHandlerConfig.DefaultCheckPlaylistMaxBackoff)
    config.checkPlaylistFactor should be(PlaylistHandlerConfig.DefaultCheckPlaylistFactor)
