/*
 * Copyright 2015-2021 The Developers Team.
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

import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration, PropertiesConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
    config.addProperty("downloadTimeout", DownloadTimeout.toSeconds)
    config.addProperty("downloadCheckInterval", DownloadCheckInterval.toSeconds)
    config.addProperty("downloadChunkSize", DownloadChunkSize)
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
class DownloadConfigSpec extends AnyFlatSpec with Matchers {

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
    val config = createConfig(createUnderlyingConfigWithout("downloadTimeout"))

    config.downloadTimeout should be(DownloadConfig.DefaultDownloadActorTimeout)
  }

  it should "use a default download check interval" in {
    val config = createConfig(createUnderlyingConfigWithout("downloadCheckInterval"))

    config.downloadCheckInterval should be(DownloadConfig.DefaultDownloadCheckInterval)
  }

  it should "use a default download chunk size" in {
    val config = createConfig(createUnderlyingConfigWithout("downloadChunkSize"))

    config.downloadChunkSize should be(DownloadConfig.DefaultDownloadChunkSize)
  }

  it should "provide a default instance" in {
    DownloadConfig.DefaultDownloadConfig.downloadChunkSize should be(DownloadConfig.DefaultDownloadChunkSize)
    DownloadConfig.DefaultDownloadConfig.downloadTimeout should be(DownloadConfig.DefaultDownloadActorTimeout)
    DownloadConfig.DefaultDownloadConfig.downloadCheckInterval should be(DownloadConfig.DefaultDownloadCheckInterval)
  }

  it should "support default values when creating an instance" in {
    val defConfig = DownloadConfig.DefaultDownloadConfig.copy(downloadTimeout = 11.minutes,
      downloadCheckInterval = 27.minutes, downloadChunkSize = 1156)

    val config = DownloadConfig(new HierarchicalConfiguration, defConfig)
    config should be(defConfig)
  }
}
