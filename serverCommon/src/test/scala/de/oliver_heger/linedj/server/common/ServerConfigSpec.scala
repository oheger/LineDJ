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

package de.oliver_heger.linedj.server.common

import org.apache.commons.configuration2.{BaseHierarchicalConfiguration, XMLConfiguration}
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.scalatest.Inspectors.forEvery
import org.scalatest.{Assertion, TryValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object ServerConfigSpec:
  /** The object to load configuration files. */
  private val configs = new Configurations
end ServerConfigSpec

/**
  * Test class for [[ServerConfig]].
  */
class ServerConfigSpec extends AnyFlatSpec, Matchers, TryValues:

  import ServerConfigSpec.*

  /**
    * Loads the configuration file with the given name from the classpath
    * resources.
    *
    * @param name the name of the file to load
    * @return the resulting configuration
    */
  private def loadConfig(name: String): XMLConfiguration =
    val configUrl = getClass.getResource("/" + name)
    configUrl should not be null
    configs.xml(configUrl)

  "ServerConfig" should "parse a basic server configuration" in :
    val config = loadConfig("test-server-config.xml")

    val serverConfig = ServerConfig(config).success.value

    val expectedServerConfig = ServerConfig(8077, None)
    serverConfig should be(expectedServerConfig)

  it should "parse a server configuration with a discovery configuration" in :
    val config = loadConfig("test-server-config-with-discovery.xml")

    val serverConfig = ServerConfig(config).success.value

    val expectedDiscoveryConfig = DiscoveryConfig(
      multicastAddress = "231.1.1.0",
      port = 8765,
      command = "whereAreYou?"
    )
    val expectedServerConfig = ServerConfig(8078, Some(expectedDiscoveryConfig))
    serverConfig should be(expectedServerConfig)

  it should "support specifying a default HTTP port" in :
    val ServerPort = 9999
    val config = new BaseHierarchicalConfiguration

    val serverConfig = ServerConfig(config, optDefaultPort = Some(ServerPort)).success.value

    val expectedServerConfig = ServerConfig(ServerPort, None)
    serverConfig should be(expectedServerConfig)

  it should "return a failure for a missing HTTP port" in :
    val serverConfig = ServerConfig(new BaseHierarchicalConfiguration)

    serverConfig.failure.exception shouldBe a[IllegalArgumentException]
    serverConfig.failure.exception.getMessage should include(ServerConfig.PropServerPort)

  /**
    * Executes a test to parse a configuration with an incomplete discovery
    * configuration.
    *
    * @param missingProperty the property this is missing
    * @return the result of the test
    */
  private def checkInvalidDiscoveryConfig(missingProperty: String): Assertion =
    val config = loadConfig("test-server-config-with-discovery.xml")
    config.clearProperty(s"${ServerConfig.SectionServer}.${ServerConfig.SectionDiscovery}.$missingProperty")

    val serverConfig = ServerConfig(config)

    serverConfig.failure.exception shouldBe a[IllegalArgumentException]
    serverConfig.failure.exception.getMessage should include(missingProperty)

  it should "return a failure for an incomplete discovery configuration" in :
    val properties = List(
      ServerConfig.PropDiscoveryMulticastAddress,
      ServerConfig.PropDiscoveryPort,
      ServerConfig.PropDiscoveryCommand
    )

    forEvery(properties)(checkInvalidDiscoveryConfig)
