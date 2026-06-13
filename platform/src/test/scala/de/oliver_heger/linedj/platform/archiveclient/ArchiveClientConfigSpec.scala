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

package de.oliver_heger.linedj.platform.archiveclient

import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

/**
  * Test class for [[ArchiveClientConfig]].
  */
class ArchiveClientConfigSpec extends AnyFlatSpec, Matchers, OptionValues:
  "ArchiveClientConfig" should "load a fully defined configuration" in :
    val config = ArchiveClientConfig(ArchiveClientConfigTestHelper.defaultTestConfig).value

    val expectedDiscoveryParams = ServerDiscovery.DiscoveryParams(
      multicastAddress = "231.1.2.3",
      port = 10101,
      requestCode = "Hello!",
      timeout = 58.seconds,
      minBackoff = 19.seconds,
      maxBackoff = 5.minutes
    )
    val expectedContentBackoff = BackoffConfig(
      minBackoff = 7.seconds,
      maxBackoff = 7.minutes,
      factor = 1.75
    )
    config.discoveryParams should be(expectedDiscoveryParams)
    config.archiveTimeout should be(38.seconds)
    config.optContentMonitorBackoff.value should be(expectedContentBackoff)

  it should "handle missing optional backoff configs" in :
    val platformConfig = ArchiveClientConfigTestHelper.testConfig: c =>
      c.clearTree("platform.mediaArchive.monitor")
    val config = ArchiveClientConfig(platformConfig).value

    config.optContentMonitorBackoff shouldBe empty

  it should "use default values for missing properties in backoff configs" in :
    val platformConfig = ArchiveClientConfigTestHelper.testConfig: c =>
      c.clearTree("platform.mediaArchive.monitor")
      c.setProperty("platform.mediaArchive.monitor.content", "")
    val config = ArchiveClientConfig(platformConfig).value

    config.optContentMonitorBackoff.value should be(ArchiveClientConfig.DefaultBackoffConfig)
