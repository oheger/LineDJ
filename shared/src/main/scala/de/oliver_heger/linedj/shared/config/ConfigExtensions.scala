/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.shared.config

import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
  * An object providing functionality to make some operations on configuration
  * settings easier.
  */
object ConfigExtensions:
  /**
    * A regular expression to parse a number followed by a unit. This is used
    * for durations.
    */
  private val RegexUnit = """([0-9]+)\s*(\S*)\s*""".r

  /**
    * An enumeration defining the supported units for durations.
    *
    * The constants defined for this enumeration can be used to convert string
    * configuration properties consisting of a number and a unit to duration
    * values.
    *
    * @param names   a set with the (alias) names for this unit
    * @param convert the function to apply the unit to a number
    */
  enum DurationUnit(val names: Set[String],
                    val convert: Int => FiniteDuration):
    /**
      * A concrete [[DurationUnit]] for milliseconds.
      */
    case MilliSeconds extends DurationUnit(Set("milliseconds", "millis", "ms"), _.milliseconds)

    /**
      * A concrete [[DurationUnit]] for seconds.
      */
    case Seconds extends DurationUnit(Set("seconds", "sec", "s"), _.seconds)

    /**
      * A concrete [[DurationUnit]] for minutes.
      */
    case Minutes extends DurationUnit(Set("minutes", "min"), _.minutes)

    /**
      * A concrete [[DurationUnit]] for hours.
      */
    case Hours extends DurationUnit(Set("hours", "h"), _.hours)

  /**
    * Transforms an object to a duration that can have multiple types. This
    * function can be used to convert values read from a configuration library.
    * Such libraries can be picky with regard to the data types of their
    * properties. If a value is just a number (when using seconds as the
    * default unit), clients must query for an [[Int]] value. For numbers with
    * a unit, a string value has to be requested instead. To handle this, this
    * function expects lazy [[Int]] and string values and tests both variants.
    *
    * @param intVal the numeric value to convert
    * @param strVal the string value (with optional unit) to convert
    * @return a [[Try]] with the resulting duration
    */
  def toDurationFromTypes(intVal: => Int, strVal: => String): Try[FiniteDuration] =
    Try(intVal.seconds) orElse :
      strVal.toDuration

  extension (s: String)
    /**
      * Converts a string to a [[DurationUnit]] constant based on the alias
      * names defined for the unit. The alias names are matched in a
      * case-insensitive way. If no unit with a matching alias name can be
      * found, result is a [[Failure]].
      *
      * @return a [[Try]] with the resulting [[DurationUnit]]
      */
    def toDurationUnit: Try[DurationUnit] =
      val durationUnitLower = s.toLowerCase.trim
      DurationUnit.values.find(_.names.contains(durationUnitLower)) match
        case Some(durationUnit) => Success(durationUnit)
        case None => Failure(new IllegalArgumentException(s"Unsupported duration unit: '$s'."))

    /**
      * Converts a string to a [[FiniteDuration]] based on a [[DurationUnit]].
      * This extension function can deal with strings that consist of a number
      * followed by an optional unit. The unit must correspond to an alias name
      * defined for one of the constants of the [[DurationUnit]] enumeration.
      * (If no unit is specified, the default unit seconds is assumed.)
      *
      * @return a [[Try]] with the converted duration
      */
    def toDuration: Try[FiniteDuration] =
      s match
        case RegexUnit(number, durationUnit) if durationUnit.nonEmpty =>
          durationUnit.toDurationUnit map : u =>
            u.convert(number.toInt)

        case RegexUnit(number, _) =>
          Success(number.toInt.seconds)

        case _ =>
          Failure(new IllegalArgumentException(s"No valid duration string: '$s'."))
