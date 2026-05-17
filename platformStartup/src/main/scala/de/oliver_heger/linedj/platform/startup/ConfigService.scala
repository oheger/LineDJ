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

package de.oliver_heger.linedj.platform.startup

import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration

/**
  * Interface of a service that provides access to the platform configuration.
  *
  * On startup, the module looks for a configuration file and reads it. Then
  * it creates an instance of this service that can be queried for the 
  * resulting configuration object. That way, all interested components can 
  * access configuration options.
  */
trait ConfigService:
  /**
    * Returns an object with the configuration loaded from the platform
    * configuration file.
    *
    * @return the platform configuration
    */
  def config: ImmutableHierarchicalConfiguration
  
