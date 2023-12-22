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

import scala.concurrent.duration._

object ConfigurationExtensions:
  /**
    * A trait representing the top of a hierarchy of duration unit
    * implementations.
    *
    * A concrete unit knows how to convert an Int number to a finite duration.
    * This is used to implement duration properties for the configuration
    * library.
    */
  sealed trait DurationUnit:
    /**
      * Converts the given value to a [[Duration]] that is represented by this
      * object.
      *
      * @param value the numeric value to be converted
      * @return the resulting ''Duration''
      */
    def toDuration(value: Int): FiniteDuration = convert(value)

    /**
      * The converter function to be applied for this unit.
      *
      * @return a function to convert an ''Int'' to this duration
      */
    protected def convert: Int => FiniteDuration

  /**
    * A concrete [[DurationUnit]] for milliseconds.
    */
  private object MillisecondsUnit extends DurationUnit:
    override protected val convert: Int => FiniteDuration = _.milliseconds

  /**
    * A concrete [[DurationUnit]] for seconds.
    */
  private object SecondsUnit extends DurationUnit:
    override protected val convert: Int => FiniteDuration = _.seconds

  /**
    * A concrete [[DurationUnit]] for minutes.
    */
  private object MinutesUnit extends DurationUnit:
    override protected val convert: Int => FiniteDuration = _.minutes

  /**
    * A concrete [[DurationUnit]] for hours.
    */
  private object HoursUnit extends DurationUnit:
    override protected val convert: Int => FiniteDuration = _.hours

  /**
    * An extension that adds some operations to the [[Configuration]] 
    * interface.
    */
  extension(c: Configuration)
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
      val unitName = c.getString(s"$key[@unit]", "seconds")
      val unit = unitByName(unitName)
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

  /**
    * Returns the [[DurationUnit]] implementation for the unit with the given
    * name. Names are case insensitive.
    *
    * @param name the name of the unit
    * @return the implementation for the unit with this name
    */
  def unitByName(name: String): DurationUnit =
    name.toLowerCase match
      case "milliseconds" => MillisecondsUnit
      case "millis" => MillisecondsUnit
      case "seconds" => SecondsUnit
      case "minutes" => MinutesUnit
      case "hours" => HoursUnit
      case _ => throw new IllegalArgumentException(s"Unsupported duration unit '$name'.")
