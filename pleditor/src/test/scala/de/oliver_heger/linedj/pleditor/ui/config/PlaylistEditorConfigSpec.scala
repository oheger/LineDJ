/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.config

import de.oliver_heger.linedj.pleditor.ui.playlist.export.ExportSettings
import net.sf.jguiraffe.gui.app.Application
import org.apache.commons.configuration.{PropertiesConfiguration, Configuration}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}

object PlaylistEditorConfigSpec {
  /**
   * Creates a configuration instance with the specified option.
   * @param key the configuration key
   * @param value the value for this key
   * @return the configuration instance
   */
  private def createWrappedConfigWithOption(key: String, value: AnyRef): Configuration = {
    val config = new PropertiesConfiguration
    config.addProperty(key, value)
    config
  }
}

/**
 * Test class for ''BrowserConfig''.
 */
class PlaylistEditorConfigSpec extends FlatSpec with Matchers with MockitoSugar {

  import PlaylistEditorConfigSpec._

  /**
   * Creates a test ''BrowserConfig'' instance that wraps the specified
   * ''Configuration''.
   * @param wrappedConfig the configuration to be wrapped
   * @return the test ''BrowserConfig''
   */
  private def createConfig(wrappedConfig: Configuration): PlaylistEditorConfig = {
    val app = mock[Application]
    when(app.getUserConfiguration).thenReturn(wrappedConfig)
    new PlaylistEditorConfig(app)
  }

  /**
   * Creates a test ''BrowserConfig'' instance that wraps an empty
   * ''Configuration''.
   * @return a tuple with the test instance and the wrapped configuration
   */
  private def createEmptyConfig(): (PlaylistEditorConfig, Configuration) = {
    val config = new PropertiesConfiguration
    (createConfig(config), config)
  }

  "A BrowserConfig" should "return the default export path" in {
    val Path = "test/export/path"
    val config = createConfig(createWrappedConfigWithOption("browser.export.defaultPath", Path))

    config.exportPath should be(Path)
  }

  it should "allow setting the default export path" in {
    val Path = "some/other/export/path"
    val (bc, config) = createEmptyConfig()

    bc.exportPath = Path
    config getString "browser.export.defaultPath" should be(Path)
  }

  it should "return the export clear mode" in {
    val ClearMode = 1
    val config = createConfig(createWrappedConfigWithOption("browser.export.defaultClearMode",
      java.lang.Integer.valueOf(ClearMode)))

    config.exportClearMode should be(ClearMode)
  }

  it should "return a default export clear mode if the setting is undefined" in {
    val (config, _) = createEmptyConfig()

    config.exportClearMode should be(ExportSettings.ClearNothing)
  }

  it should "allow setting the default export clear mode" in {
    val ClearMode = ExportSettings.ClearAll
    val (bc, config) = createEmptyConfig()

    bc.exportClearMode = ClearMode
    config getInt "browser.export.defaultClearMode" should be(ClearMode)
  }
}
