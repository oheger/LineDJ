/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.archivecommon.download

import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

object DownloadConfigSpec {
  /** The timeout for an inactive download actor. */
  private val DownloadTimeout = 2.hour

  /** The check interval for download actors. */
  private val DownloadCheckInterval = 22.minutes

  /** The download chunk size. */
  private val DownloadChunkSize = 8192

  /**
    * Creates the ''Configuration'' object with settings for download
    * operations.
    *
    * @return the ''Configuration''
    */
  private def createUnderlyingConfig(): Configuration = {
    val config = new PropertiesConfiguration
    config.addProperty("media.downloadTimeout", DownloadTimeout.toSeconds)
    config.addProperty("media.downloadCheckInterval", DownloadCheckInterval.toSeconds)
    config.addProperty("media.downloadChunkSize", DownloadChunkSize)
    config
  }

  /**
    * Creates a ''Configuration'' object with download options and removes the
    * specified property. This is used to test default values.
    *
    * @param property the property to be removed
    * @return the ''Configuration''
    */
  private def createUnderlyingConfigWithout(property: String): Configuration = {
    val config = createUnderlyingConfig()
    config clearProperty property
    config
  }

  /**
    * Creates a test ''DownloadConfig'' instance.
    *
    * @param cc the underlying configuration to be used
    * @return the test download config
    */
  private def createConfig(cc: Configuration = createUnderlyingConfig()): DownloadConfig =
    DownloadConfig(cc)
}

/**
  * Test class for ''DownloadConfig''.
  */
class DownloadConfigSpec extends FlatSpec with Matchers {

  import DownloadConfigSpec._

  "A DownloadConfig" should "return the correct download timeout" in {
    createConfig().downloadTimeout should be(DownloadTimeout)
  }

  it should "return the correct download check interval" in {
    createConfig().downloadCheckInterval should be(DownloadCheckInterval)
  }

  it should "return the correct download chunk size" in {
    createConfig().downloadChunkSize should be(DownloadChunkSize)
  }

  it should "use a default download timeout" in {
    val config = createConfig(createUnderlyingConfigWithout("media.downloadTimeout"))

    config.downloadTimeout should be(DownloadConfig.DefaultDownloadActorTimeout)
  }

  it should "use a default download check interval" in {
    val config = createConfig(createUnderlyingConfigWithout("media.downloadCheckInterval"))

    config.downloadCheckInterval should be(DownloadConfig.DefaultDownloadCheckInterval)
  }

  it should "use a default download chunk size" in {
    val config = createConfig(createUnderlyingConfigWithout("media.downloadChunkSize"))

    config.downloadChunkSize should be(DownloadConfig.DefaultDownloadChunkSize)
  }
}
