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
    * The index of the field for hours. This has a special meaning for
    * day-of-week intervals; therefore, it is stored as a constant.
    */
  private lazy val IdxHourField = Fields indexOf ChronoField.HOUR_OF_DAY

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
    * Creates an ''IntervalQuery'' for an interval of days in a week. As
    * arguments constants for the days can be passed in (mind the correct
    * order, so that from is actually less than until).
    *
    * @param from  the start day (inclusive)
    * @param until the end day (exclusive)
    * @return the new interval query
    */
  def weekDays(from: Int, until: Int): IntervalQuery = {
    val fSet = (date: LocalDateTime, value: Int) => trimTo(date.`with`(ChronoField.DAY_OF_WEEK,
      value), IdxHourField, 0)
    val fInc = (date: LocalDateTime) => date plusDays 1
    fieldRangeF(ChronoField.DAY_OF_WEEK, fSet, fInc, from, until)
  }

  /**
    * Combines two interval queries to a single one. The order of the parameter
    * is important. The first query must use a larger time unit than the second
    * one, i.e. the second query must refine the first one. For instance, the
    * first query could be an interval of hours, the second one an interval of
    * minutes. The resulting query returns an ''Inside'' result if and only if
    * the passed in date lies in the hours interval and in the minutes
    * interval.
    *
    * @param coarser the query on a larger temporal unit
    * @param finer   the query on a finer temporal unit
    * @return the combined interval query
    */
  def combine(coarser: IntervalQuery, finer: IntervalQuery): IntervalQuery = date => {
    coarser(date) match {
      case rb@Before(start) =>
        finer(start.value) match {
          case f: Before => f
          case _ => rb
        }

      case Inside(until, next) =>
        finer(date) match {
          case After =>
            next match {
              case Some(d) =>
                finer(d.value)
              case _ => After
            }
          case r => r
        }

      case After => After
    }
  }

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
    val fSet = (date: LocalDateTime, value: Int) => trimTo(date, fieldIdx, value)
    val fInc = (date: LocalDateTime) => increase(date, fieldIdx)
    fieldRangeF(field, fSet, fInc, from, until)
  }

  /**
    * A generic method for creating an interval query for a range interval. How
    * the relevant date field is set and how an increment is done is specified
    * in form of functions. Therefore, this method also works with date fields
    * that are not in a logical sequence of intervals, e.g. day of week or week
    * of year.
    *
    * @param field the ''ChronoField'' determining the unit
    * @param fSet the function to set a field in the date
    * @param fInc the function to increment the date
    * @param from the start of the interval (inclusive)
    * @param until the end of the interval (exclusive)
    * @return the new interval query
    */
  private def fieldRangeF(field: ChronoField, fSet: (LocalDateTime, Int) => LocalDateTime,
                          fInc: LocalDateTime => LocalDateTime, from: Int, until: Int):
  IntervalQuery =
    date => {
      if (date.get(field) >= until) After
      else if (date.get(field) < from) {
        Before(new LazyDate(fSet(date, from)))
      } else {
        Inside(new LazyDate(fInc(fSet(date, until - 1))),
          if (date.get(field) == until - 1) None
          else Some(new LazyDate(fSet(date, date.get(field) + 1))))
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
