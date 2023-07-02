/*
 * Copyright 2015-2023 The Developers Team.
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

import org.apache.commons.configuration.Configuration

import scala.concurrent.duration.*

object DurationUnits:
  /**
    * Return the constant representing the unit with the given name. In
    * contrast to the default ''valueOf'' function, this function matches unit
    * names in a case-insensitive way.
    *
    * @param name the name of the unit
    * @return the constant for the unit with this name
    */
  def byUnit(name: String): DurationUnits =
    valueOf(name.toLowerCase.capitalize)

/**
  * An enumeration class defining the units supported by configuration
  * properties of type ''Duration''. This is used together with the
  * ''getDuration()'' extension function on [[Configuration]].
  */
enum DurationUnits(convert: Int => FiniteDuration):
  /**
    * Converts the given value to a [[Duration]] that is represented by this
    * constant.
    *
    * @param value the numeric value to be converted
    * @return the resulting ''Duration''
    */
  def toDuration(value: Int): FiniteDuration = convert(value)

  case Milliseconds extends DurationUnits(_.milliseconds)
  case Seconds extends DurationUnits(_.seconds)
  case Minutes extends DurationUnits(_.minutes)
  case Hours extends DurationUnits(_.hours)
end DurationUnits

extension (c: Configuration)

  /**
    * Returns the configuration value with the given key of type [[Duration]].
    * It is possible to specify the unit of the duration in an attribute of the
    * given key named ''unit''. This must reference one of the constants
    * defined by the [[DurationUnits]] enumeration (ignoring case). If no unit
    * is explicitly specified, seconds is used as default.
    */
  def getDuration(key: String): FiniteDuration =
    val unitName = c.getString(s"$key[@unit]", DurationUnits.Seconds.toString)
    val unit = DurationUnits.byUnit(unitName)
    unit.toDuration(c.getInt(key))

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
