/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.client.config

import de.oliver_heger.linedj.shared.config.ConfigExtensions
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDurationUnit
import org.apache.commons.configuration.Configuration

import scala.concurrent.duration.*

object ConfigurationExtensions:
  /**
    * An extension that adds some operations to the [[Configuration]]
    * interface.
    */
  extension (c: Configuration)
    /**
      * Returns the configuration value with the given key of type [[Duration]].
      * It is possible to specify the unit of the duration in an attribute of the
      * given key named ''unit''. This must reference one of the constants
      * defined by the [[DurationUnit]] enumeration (ignoring case). If no unit
      * is explicitly specified, seconds is used as default.
      *
      * @param key the key
      * @return the value of type ''Duration''
      */
    def getDuration(key: String): FiniteDuration =
      val unitKey = s"$key[@unit]"
      val triedDuration = if c.containsKey(unitKey) then
        c.getString(unitKey).toDurationUnit.map(_.convert(c.getInt(key)))
      else
        ConfigExtensions.toDurationFromTypes(c.getInt(key), c.getString(key))
      triedDuration.get

    /**
      * Returns the configuration value with the given key of type [[Duration]]
      * or the provided default value if the key does not exist.
      *
      * @param key     the key
      * @param default the default value
      * @return the value of type ''Duration''
      */
    def getDuration(key: String, default: FiniteDuration): FiniteDuration =
      if c.containsKey(key) then getDuration(key)
      else default
