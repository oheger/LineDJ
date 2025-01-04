/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.utils

/**
  * A trait that provides access to system properties.
  *
  * This trait defines a function for querying the value of a system
  * property. An option with the value is returned.
  *
  * By overriding this method in test classes, it is easy to inject specific
  * property values without having to deal with real system properties.
  * (Obviously, SBT does not support changing system properties at runtime.)
  */
trait SystemPropertyAccess:
  /**
    * Queries a system property and returns its value.
    *
    * @param key the key of the property
    * @return an option for the property's value
    */
  def getSystemProperty(key: String): Option[String] = Option(System getProperty key)
