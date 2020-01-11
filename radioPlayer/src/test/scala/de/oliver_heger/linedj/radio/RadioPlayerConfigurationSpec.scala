/*
 * Copyright 2015-2020 The Developers Team.
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

import org.apache.commons.configuration.{HierarchicalConfiguration, XMLConfiguration}
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._

object RadioPlayerConfigurationSpec {
  /** The name of the test configuration file. */
  private val ConfigFile = ".lineDJ-radioplayer.xml"

  /**
    * Reads the test configuration file.
    *
    * @return the configuration
    */
  private def readConfiguration(): HierarchicalConfiguration =
  new XMLConfiguration(ConfigFile)

  /**
    * Obtains the configuration for the radio sources.
    *
    * @return the test radio sources configuration
    */
  private def sourcesConfiguration(): RadioSourceConfig =
  RadioSourceConfig(readConfiguration())

  /**
    * Obtains the error handling configuration from the test configuration.
    *
    * @return the error handling configuration
    */
  private def errorConfiguration(): ErrorHandlingStrategy.Config = {
    val config = readConfiguration()
    ErrorHandlingStrategy.createConfig(config, sourcesConfiguration())
  }
}

/**
  * A test class for different kinds of configuration for the radio player
  * application.
  *
  * This is not a test class for a specific class, but tests configuration
  * processing in general. This also serves documentation purposes, as a full
  * configuration is part of the test resources. This configuration is read,
  * and some settings are queried using the official player configuration
  * classes.
  */
class RadioPlayerConfigurationSpec extends FlatSpec with Matchers {

  import RadioPlayerConfigurationSpec._

  "A radio player configuration" should "contain radio sources" in {
    val srcConfig = sourcesConfiguration()

    srcConfig.sources.size should be > 2
  }

  it should "order radio sources by their ranking" in {
    val srcConfig = sourcesConfiguration()

    val firstSource = srcConfig.sources.head._2
    val lastSource = srcConfig.sources.last._2
    srcConfig.ranking(firstSource) should be > srcConfig.ranking(lastSource)
  }

  it should "support exclusions for radio sources" in {
    val srcConfig = sourcesConfiguration()

    srcConfig.exclusions.size should be(srcConfig.sources.size)
    srcConfig.exclusions.values.exists(_.nonEmpty) shouldBe true
  }

  it should "support an error configuration" in {
    val errConfig = errorConfiguration()

    errConfig.retryInterval should be(1000.millis)
    errConfig.retryIncrementFactor should be(2.0)
  }
}
