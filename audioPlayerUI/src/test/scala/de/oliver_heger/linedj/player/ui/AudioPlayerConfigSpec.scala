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

package de.oliver_heger.linedj.player.ui

import org.apache.commons.configuration.PropertiesConfiguration
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''AudioPlayerConfig''.
  */
class AudioPlayerConfigSpec extends FlatSpec with Matchers {
  "An AudioPlayerConfig" should "provide access to all properties" in {
    val MaxFieldSize = 42
    val RotationSpeed = 3
    val SkipBackwardsThreshold = 8
    val c = new PropertiesConfiguration
    c.addProperty(AudioPlayerConfig.PropMaxFieldSize, MaxFieldSize)
    c.addProperty(AudioPlayerConfig.PropRotationSpeed, RotationSpeed)
    c.addProperty(AudioPlayerConfig.PropSkipBackwardsThreshold, SkipBackwardsThreshold)

    val config = AudioPlayerConfig(c)
    config.maxUIFieldSize should be(MaxFieldSize)
    config.rotationSpeed should be(RotationSpeed)
    config.skipBackwardsThreshold should be(SkipBackwardsThreshold)
  }

  it should "use correct default values" in {
    val config = AudioPlayerConfig(new PropertiesConfiguration)

    config.maxUIFieldSize should be(Integer.MAX_VALUE)
    config.rotationSpeed should be(AudioPlayerConfig.DefRotationSpeed)
    config.skipBackwardsThreshold should be(AudioPlayerConfig.DefSkipBackwardsThreshold)
    config.autoStartMode should be(AudioPlayerConfig.AutoStartNever)
  }

  it should "detect auto start mode always" in {
    val c = new PropertiesConfiguration
    c.addProperty(AudioPlayerConfig.PropAutoStartPlayback, "Always")
    val config = AudioPlayerConfig(c)

    config.autoStartMode should be(AudioPlayerConfig.AutoStartAlways)
  }

  it should "detect auto start mode closed" in {
    val c = new PropertiesConfiguration
    c.addProperty(AudioPlayerConfig.PropAutoStartPlayback, "CLOSED")
    val config = AudioPlayerConfig(c)

    config.autoStartMode should be(AudioPlayerConfig.AutoStartIfClosed)
  }

  it should "handle an invalid auto start mode" in {
    val c = new PropertiesConfiguration
    c.addProperty(AudioPlayerConfig.PropAutoStartPlayback, "an invalid mode!")
    val config = AudioPlayerConfig(c)

    config.autoStartMode should be(AudioPlayerConfig.AutoStartNever)
  }
}
