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

import org.apache.commons.configuration2.{BaseHierarchicalConfiguration, ImmutableHierarchicalConfiguration, XMLConfiguration}
import org.apache.commons.configuration2.builder.fluent.Configurations

/**
  * A helper object providing functionality related to the configuration of the
  * archive client. It is used by multiple test classes that require such a 
  * configuration.
  *
  * The object manages a standard test configuration and offers functions to
  * create derived configurations with modified properties.
  */
object ArchiveClientConfigTestHelper:
  /** The name of the test default configuration file. */
  private val TestConfigFile = "test-archive-platform-config.xml"

  /**
    * Stores the default test configuration. Based on this object, modified
    * configurations can be created.
    */
  private val defaultConfig = loadDefaultConfig()

  /**
    * Loads the test configuration file with standard settings for the archive
    * client configuration.
    *
    * @return the test configuration
    */
  private def loadDefaultConfig(): XMLConfiguration =
    val configs = new Configurations
    configs.xml(TestConfigFile)

  /**
    * Returns the default test configuration managed by this helper. It has 
    * been initialized from a file resource.
    *
    * @return the default test configuration
    */
  def defaultTestConfig: ImmutableHierarchicalConfiguration = defaultConfig

  /**
    * Returns a copy of the default test configuration with modifications 
    * applied by the given modifier function. The function gets passed a
    * modifiable copy of the default configuration and can change it as
    * desired.
    *
    * @param f the function that modified the default configuration
    * @return the copy of the default test configuration
    */
  def testConfig(f: BaseHierarchicalConfiguration => Unit): BaseHierarchicalConfiguration =
    val modifiedConfig = new BaseHierarchicalConfiguration(defaultConfig)
    f(modifiedConfig)
    modifiedConfig
