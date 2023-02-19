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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.player.engine.radio.RadioSource
import org.apache.commons.configuration.{HierarchicalConfiguration, XMLConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

object RadioPlayerClientConfigSpec {
  /** The name of the test configuration file. */
  private val ConfigFile = "test-radio-configuration.xml"

  /**
    * Reads the test configuration file.
    *
    * @return the configuration
    */
  private def readConfigurationFile(): HierarchicalConfiguration = new XMLConfiguration(ConfigFile)

  /**
    * Loads a [[RadioPlayerClientConfig]] from the test configuration file.
    *
    * @return the configuration created from the file
    */
  private def loadConfig(): RadioPlayerClientConfig = RadioPlayerClientConfig(readConfigurationFile())
}

/**
  * Test class for [[RadioPlayerClientConfig]].
  */
class RadioPlayerClientConfigSpec extends AnyFlatSpec with Matchers {

  import RadioPlayerClientConfigSpec._

  "A radio player configuration" should "contain radio sources" in {
    val srcConfig = loadConfig().sourceConfig

    srcConfig.namedSources.size should be > 2
  }

  it should "order radio sources by their ranking" in {
    val srcConfig = loadConfig().sourceConfig

    val firstSource = srcConfig.namedSources.head._2
    val lastSource = srcConfig.namedSources.last._2
    srcConfig.ranking(firstSource) should be > srcConfig.ranking(lastSource)
  }

  it should "support exclusions for radio sources" in {
    val srcConfig = loadConfig().sourceConfig

    srcConfig.namedSources.map(_._2).exists { source =>
      srcConfig.exclusions(source).nonEmpty
    }
  }

  it should "support an error configuration" in {
    val errConfig = loadConfig().errorConfig

    errConfig.retryInterval should be(1000.millis)
    errConfig.retryIncrementFactor should be(2.0)
    errConfig.maxRetries should be(3)
    errConfig.recoveryTime should be(20)
    errConfig.recoverMinFailedSources should be(2)
  }

  it should "correct a retry interval that is too small" in {
    val playerConfig = new HierarchicalConfiguration
    playerConfig.addProperty("radio.error.retryInterval", 9)

    val config = RadioPlayerClientConfig(playerConfig)
    config.errorConfig.retryInterval should be(10.millis)
  }

  it should "correct an increment factor that is too small" in {
    val playerConfig = new HierarchicalConfiguration
    playerConfig.addProperty("radio.error.retryIncrement", 1)

    val config = RadioPlayerClientConfig(playerConfig)
    config.errorConfig.retryIncrementFactor should be(1.1)
  }

  it should "read further configuration properties" in {
    val config = loadConfig()

    config.initialDelay should be(1500)
    config.metaMaxLen should be(44)
    config.metaRotateSpeed should be(0.5)
  }

  it should "apply defaults for unspecified configuration settings" in {
    val config = RadioPlayerClientConfig(new HierarchicalConfiguration)

    config.sourceConfig.namedSources shouldBe empty
    config.sourceConfig.exclusions(RadioSource("someRadioSource")) shouldBe empty
    config.errorConfig.retryInterval should be(RadioPlayerClientConfig.DefaultRetryInterval.millis)
    config.errorConfig.retryIncrementFactor should be(RadioPlayerClientConfig.DefaultRetryIncrement)
    config.errorConfig.maxRetries should be(RadioPlayerClientConfig.DefaultMaxRetries)
    config.errorConfig.recoveryTime should be(RadioPlayerClientConfig.DefaultRecoveryTime)
    config.errorConfig.recoverMinFailedSources should be(RadioPlayerClientConfig.DefaultMinFailuresForRecovery)
    config.initialDelay should be(RadioPlayerClientConfig.DefaultInitialDelay)
    config.metaMaxLen should be(RadioPlayerClientConfig.DefaultMetadataMaxLen)
    config.metaRotateSpeed should be(RadioPlayerClientConfig.DefaultMetadataRotateScale)
  }
}
