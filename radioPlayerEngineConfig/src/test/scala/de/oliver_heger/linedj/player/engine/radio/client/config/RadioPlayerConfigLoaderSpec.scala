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

package de.oliver_heger.linedj.player.engine.radio.client.config

import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.config.RadioPlayerConfig
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
  * Test class for [[RadioPlayerConfigLoader]].
  */
class RadioPlayerConfigLoaderSpec extends AnyFlatSpec with Matchers:
  "RadioPlayerConfigLoader" should "read the properties from a configuration" in:
    val Prefix = "radio.player.config"
    val config = new HierarchicalConfiguration

    def set(key: String, value: Any): Unit =
      config.addProperty(s"$Prefix.$key", value)

    def setWithUnit(key: String, value: Int, unit: String): Unit =
      set(key, value)
      set(key + "[@unit]", unit)

    setWithUnit(RadioPlayerConfigLoader.PropMaxEvalDelay, 123, "minutes")
    setWithUnit(RadioPlayerConfigLoader.PropRetryFailedReplacement, 21, "seconds")
    setWithUnit(RadioPlayerConfigLoader.PropRetryFailedSource, 17, "seconds")
    set(RadioPlayerConfigLoader.PropRetryFailedSourceIncrement, 1.5)
    setWithUnit(RadioPlayerConfigLoader.PropMaxRetryFailedSource, 24, "hours")
    setWithUnit(RadioPlayerConfigLoader.PropSourceCheckTimeout, 55, "seconds")
    setWithUnit(RadioPlayerConfigLoader.PropMetadataCheckTimeout, 29, "seconds")
    setWithUnit(RadioPlayerConfigLoader.PropStreamCacheTime, 4444, "milliseconds")
    setWithUnit(RadioPlayerConfigLoader.PropStalledPlaybackCheck, 11, "seconds")

    val playerConfig = PlayerConfigLoader.DefaultPlayerConfig.copy(inMemoryBufferSize = 987456321)
    val radioConfig = RadioPlayerConfigLoader.loadRadioPlayerConfig(config, Prefix, playerConfig)

    radioConfig.playerConfig should be(playerConfig)
    radioConfig.maximumEvalDelay should be(123.minutes)
    radioConfig.retryFailedReplacement should be(21.seconds)
    radioConfig.retryFailedSource should be(17.seconds)
    radioConfig.retryFailedSourceIncrement should be(1.5)
    radioConfig.maxRetryFailedSource should be(24.hours)
    radioConfig.sourceCheckTimeout should be(55.seconds)
    radioConfig.metadataCheckTimeout should be(29.seconds)
    radioConfig.streamCacheTime should be(4444.millis)
    radioConfig.stalledPlaybackCheck should be(11.seconds)

  /**
    * Helper function for testing whether the given radio configuration
    * contains only default values.
    *
    * @param radioConfig the configuration to check
    */
  private def checkDefaultConfig(radioConfig: RadioPlayerConfig): Unit =
    radioConfig.playerConfig should be(PlayerConfigLoader.DefaultPlayerConfig)
    radioConfig.maximumEvalDelay should be(RadioPlayerConfigLoader.DefaultMaxEvalDelay)
    radioConfig.retryFailedReplacement should be(RadioPlayerConfigLoader.DefaultRetryFailedReplacement)
    radioConfig.retryFailedSource should be(RadioPlayerConfigLoader.DefaultRetryFailedSource)
    radioConfig.retryFailedSourceIncrement should be(RadioPlayerConfigLoader.DefaultRetryFailedSourceIncrement)
    radioConfig.maxRetryFailedSource should be(RadioPlayerConfigLoader.DefaultMaxRetryFailedSource)
    radioConfig.sourceCheckTimeout should be(RadioPlayerConfigLoader.DefaultSourceCheckTimeout)
    radioConfig.metadataCheckTimeout should be(RadioPlayerConfigLoader.DefaultMetadataCheckTimeout)
    radioConfig.streamCacheTime should be(RadioPlayerConfigLoader.DefaultStreamCacheTime)
    radioConfig.stalledPlaybackCheck should be(RadioPlayerConfigLoader.DefaultStalledPlaybackCheck)

  it should "create a configuration with default values" in:
    val radioConfig = RadioPlayerConfigLoader.loadRadioPlayerConfig(new HierarchicalConfiguration, "test",
      PlayerConfigLoader.DefaultPlayerConfig)

    checkDefaultConfig(radioConfig)

  it should "handle a path prefix with a trailing dot" in:
    val Prefix = "radio.player.config.dot."
    val config = new HierarchicalConfiguration
    config.addProperty(Prefix + RadioPlayerConfigLoader.PropRetryFailedSourceIncrement, 1.25)

    val radioConfig = RadioPlayerConfigLoader.loadRadioPlayerConfig(config, Prefix,
      PlayerConfigLoader.DefaultPlayerConfig)

    radioConfig.retryFailedSourceIncrement should be(1.25)

  it should "provide a default radio player config" in:
    val radioConfig = RadioPlayerConfigLoader.DefaultRadioPlayerConfig

    checkDefaultConfig(radioConfig)
