/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.interval

import java.time.LocalDateTime
import java.time.temporal.ChronoField

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside,
IntervalQuery}

import scala.annotation.tailrec

/**
  * An object defining functions for defining time intervals and querying their
  * relations to dates.
  */
object IntervalQueries {
  /**
    * A list defining an order of fields that need to be processed when
    * trimming a date.
    */
  private val Fields = Array(ChronoField.YEAR, ChronoField.MONTH_OF_YEAR, ChronoField
    .DAY_OF_MONTH, ChronoField.HOUR_OF_DAY, ChronoField.MINUTE_OF_HOUR, ChronoField
    .SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND)

  /**
    * Creates an ''IntervalQuery'' for an hour interval.
    *
    * @param from  the start hour (inclusive)
    * @param until the end hour (exclusive)
    * @return the new interval query
    */
  def hours(from: Int, until: Int): IntervalQuery =
    fieldRange(ChronoField.HOUR_OF_DAY, from, until)

  /**
    * Creates an ''IntervalQuery'' for a minutes interval.
    *
    * @param from  the start minute (inclusive)
    * @param until the end minute (inclusive)
    * @return the new interval query
    */
  def minutes(from: Int, until: Int): IntervalQuery =
    fieldRange(ChronoField.MINUTE_OF_HOUR, from, until)

  /**
    * Creates an ''IntervalQuery'' for a dates interval.
    *
    * @param from  the start day (inclusive)
    * @param until the end day (exclusive)
    * @return the new interval query
    */
  def days(from: Int, until: Int): IntervalQuery =
    fieldRange(ChronoField.DAY_OF_MONTH, from, until)

  /**
    * Creates an ''IntervalQuery'' for a months interval.
    *
    * @param from  the start month (inclusive)
    * @param until the end month (exclusive)
    * @return the new interval query
    */
  def months(from: Int, until: Int): IntervalQuery =
    fieldRange(ChronoField.MONTH_OF_YEAR, from, until)

  /**
    * Creates an ''IntervalQuery'' for an interval in a specific temporal
    * unit.
    *
    * @param field the ''ChronoField'' determining the unit
    * @param from  the start value of the interval (inclusive)
    * @param until the end value of the interval (exclusive)
    * @return the new interval query
    */
  private def fieldRange(field: ChronoField, from: Int, until: Int): IntervalQuery = {
    val fieldIdx = Fields indexOf field
    date => {
      if (date.get(field) >= until) After
      else if (date.get(field) < from) {
        Before(new LazyDate(trimTo(date, fieldIdx, from)))
      } else {
        Inside(new LazyDate(increase(trimTo(date, fieldIdx, until - 1), fieldIdx)),
          if (date.get(field) == until - 1) None
          else Some(new LazyDate(trimTo(date, fieldIdx, date.get(field) + 1))))
      }
    }
  }

  /**
    * Increases the specified field of the given date. The method checks
    * whether whether the value of the field can be incremented. If this is not
    * the case (because the maximum value is already reached), the field with
    * the enclosing temporal unit is increased.
    *
    * @param date     the date
    * @param fieldIdx the index of the field to be incremented
    * @return the updated date
    */
  @tailrec def increase(date: LocalDateTime, fieldIdx: Int): LocalDateTime = {
    val value = date.get(Fields(fieldIdx))
    val range = date.range(Fields(fieldIdx))

    if (value < range.getMaximum) {
      date.`with`(Fields(fieldIdx), value + 1)
    } else {
      increase(date.`with`(Fields(fieldIdx), range.getMinimum), fieldIdx - 1)
    }
  }

  /**
    * Sets the value of a specific field in the given date and sets all
    * succeeding smaller fields to 0.
    *
    * @param date     the date
    * @param fieldIdx the index of the field to be updated
    * @param value    the value to be set
    * @return the updated date
    */
  private def trimTo(date: LocalDateTime, fieldIdx: Int, value: Int): LocalDateTime = {
    Fields.splitAt(fieldIdx + 1)._2.foldLeft(date.`with`(Fields(fieldIdx), value))((d, f) =>
      d.`with`(f, f.range().getMinimum))
  }
}
