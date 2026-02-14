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

package de.oliver_heger.linedj.archive.server

import org.apache.commons.configuration2.Configuration
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.apache.commons.configuration2.ex.ConfigurationException
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

object ArchiveServerConfigSpec:
  /** The name of the test configuration file used by this test suite. */
  private val TestConfigFile = "test-base-archive-server-config.xml"
  
  /**
    * The name of a property that is loaded from the test configuration by the
    * test config loader implementation.
    */
  private val CustomProperty = "test.value"

  /**
    * A function used as test config loader. The function extracts a test
    * property of type ''Int''.
    * @param config the input configuration
    * @return the value extracted by the loader
    */
  def loadArchiveConfig(config: Configuration): Int =
    config.getInt(CustomProperty)
end ArchiveServerConfigSpec

/**
  * Test class for [[ArchiveServerConfig]].
  */
class ArchiveServerConfigSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("ArchiveServerConfigSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()
    
  import ArchiveServerConfigSpec.* 

  /**
    * Loads the test configuration file and parses it to a [[Configuration]]
    * instance.
    *
    * @return the [[Configuration]] loaded from the test config file
    */
  private def loadTestConfig(): Configuration =
    val configs = new Configurations
    configs.xml(TestConfigFile)

  "ArchiveServerConfig" should "successfully load the configuration" in :
    ArchiveServerConfig(TestConfigFile)(loadArchiveConfig) map : config =>
      config.serverPort should be(8085)
      config.archiveConfig should be(42)

  it should "return a failed Future for a non-existing configuration file" in :
    val configFileName = "non-existing-config.xml"
    val futEx = recoverToExceptionIf[ConfigurationException]:
      ArchiveServerConfig(configFileName)(loadArchiveConfig)

    futEx map : ex =>
      ex.getMessage should include(configFileName)

  it should "use default values for unspecified configuration properties" in :
    val config = loadTestConfig()
    config.clearProperty(ArchiveServerConfig.PropServerPort)

    val serverConfig = ArchiveServerConfig(config)(loadArchiveConfig)

    serverConfig.serverPort should be(ArchiveServerConfig.DefaultServerPort)
    serverConfig.timeout should be(ArchiveServerConfig.DefaultServerTimeout)

  it should "parse a numeric timeout" in :
    val TimeoutSecs = 27
    val config = loadTestConfig()
    config.setProperty(ArchiveServerConfig.PropServerTimeout, TimeoutSecs)

    val serverConfig = ArchiveServerConfig(config)(loadArchiveConfig)

    serverConfig.timeout should be(TimeoutSecs.seconds)

  it should "parse a timeout with a unit" in :
    val config = loadTestConfig()
    config.setProperty(ArchiveServerConfig.PropServerTimeout, "2min")

    val serverConfig = ArchiveServerConfig(config)(loadArchiveConfig)

    serverConfig.timeout should be(120.seconds)
