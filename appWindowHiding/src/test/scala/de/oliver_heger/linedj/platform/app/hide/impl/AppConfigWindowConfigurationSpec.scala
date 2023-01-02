/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.app.hide.impl

import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.app.hide.ApplicationWindowConfiguration
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration, PropertiesConfiguration}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object AppConfigWindowConfigurationSpec {
  /** A name for a configuration. */
  private val AppName = "MyTestApp"

  /**
    * Creates a configuration with a window management section. Whether storage
    * is enabled can be determined via the passed in flag.
    *
    * @param enabled flag whether the storage key should be set to true
    * @return the configuration
    */
  def initConfig(enabled: Boolean = true): HierarchicalConfiguration = {
    val config = new HierarchicalConfiguration
    config.addProperty("platform.windowManagement.config", enabled)
    config
  }

  /**
    * Convenience method that returns a sub configuration pointing on the
    * window management section. This configuration can be easier evaluated or
    * manipulated.
    *
    * @param config the original configuration
    * @return the sub configuration pointing to the relevant data
    */
  def subConfig(config: HierarchicalConfiguration): HierarchicalConfiguration =
    config.configurationAt("platform.windowManagement")
}

/**
  * Test class for ''AppConfigWindowConfiguration''.
  */
class AppConfigWindowConfigurationSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import AppConfigWindowConfigurationSpec._

  /**
    * Initializes an ''ApplicationWindowConfiguration'' object from the given
    * configuration and returns it.
    *
    * @param config the configuration
    * @return the ''ApplicationWindowConfiguration''
    */
  private def windowConfigFor(config: Configuration): ApplicationWindowConfiguration =
    AppConfigWindowConfiguration(config).get

  /**
    * Creates a mock for an application with the given name.
    *
    * @param name the application name
    * @return the mock application
    */
  private def applicationMock(name: String = AppName): ClientApplication = {
    val app = mock[ClientApplication]
    when(app.appName).thenReturn(name)
    app
  }

  "An AppConfigWindowConfiguration" should "ignore a config without storage key" in {
    val config = new PropertiesConfiguration

    AppConfigWindowConfiguration(config) shouldBe empty
  }

  it should "ignore a config with the storage key disabled" in {
    val config = initConfig(enabled = false)

    AppConfigWindowConfiguration(config) shouldBe empty
  }

  it should "consider an undefined application as visible" in {
    val config = windowConfigFor(initConfig())

    config.isWindowVisible(applicationMock()) shouldBe true
  }

  it should "consider an application invisible if it is listed in the configuration" in {
    val data = initConfig()
    val sub = subConfig(data)
    val config = windowConfigFor(data)

    sub.addProperty("apps." + AppName, false)
    config.isWindowVisible(applicationMock()) shouldBe false
  }

  it should "evaluate the value of the app visibile state property" in {
    val data = initConfig()
    val sub = subConfig(data)
    val config = windowConfigFor(data)

    sub.addProperty("apps." + AppName, true)
    config.isWindowVisible(applicationMock()) shouldBe true
  }

  it should "store the visible state of an invisible application" in {
    val data = initConfig()
    val config = windowConfigFor(data)

    config.setWindowVisible(applicationMock(), visible = false)
    subConfig(data).getBoolean("apps." + AppName) shouldBe false
  }

  it should "remove the key for a visible application" in {
    val data = initConfig()
    val config = windowConfigFor(data)

    config.setWindowVisible(applicationMock(), visible = true)
    subConfig(data).containsKey("apps." + AppName) shouldBe false
  }

  it should "recognize main applications" in {
    val data = initConfig()
    val sub = subConfig(data)
    val OtherAppName = "Some other app"
    sub.addProperty("main.name", Array(AppName, OtherAppName + "_not"))
    val config = windowConfigFor(data)

    config.isMainApplication(applicationMock()) shouldBe true
    config.isMainApplication(applicationMock(OtherAppName)) shouldBe false
  }

  it should "handle an undefined key for main applications" in {
    val config = windowConfigFor(initConfig())

    config.isMainApplication(applicationMock()) shouldBe false
  }
}
