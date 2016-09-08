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

import java.time.{DayOfWeek, LocalDateTime}
import java.time.temporal.{ChronoField, TemporalField}

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes._

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
    * Returns a pre-defined ''ResultComparator'' that prefers ''Inside''
    * results with later ''until'' dates. This function implements the
    * following ordering:
    * $ - ''Inside'' results are less than all other results
    * $ - ''Before'' results are less than ''After'' results
    * $ - Two ''Inside'' results are compared by their ''until'' date; the one
    * with the larger date is considered less
    * $ - Two ''Before'' results are compared by their ''start'' date; the one
    * with the smaller date is considered less
    * $ - For two ''After'' results this function returns '''true'''
    *
    * @return the ''longest inside'' result comparator
    */
  val LongestInside: ResultComparator = longestInsideCompare

  /**
    * A pre-defined ''ResultComparator'' that prefers results which are not
    * or only short inside a temporal interval. This comparator is the
    * negation of the ''LongestInside'' comparator.
    */
  val ShortestInside: ResultComparator = shortestInsideCompare

  /**
    * A ''ResultSelector'' function that selects the ''longest Inside result''.
    * This function uses the comparator returned by [[LongestInside]] to
    * compare two result objects. The preferred one is selected.
    */
  val LongestInsideSelector: ResultSelector = longestInsideSelect

  /**
    * A special ''Before'' result with a date in the very far future. This
    * result may be used as default value for queries that can return an
    * optional result.
    */
  val BeforeForEver = Before(new LazyDate(forEverDate()))

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
    * Convenience function that returns a ''weekDays'' query which selects all
    * working days (from Monday to Friday).
    *
    * @return a day-of-week query for work days
    */
  def workDays(): IntervalQuery =
    weekDays(DayOfWeek.MONDAY.getValue, DayOfWeek.FRIDAY.getValue + 1)

  /**
    * Convenience function that returns a ''weekDays'' query which selects the
    * days on a week end (Saturday and Sunday).
    *
    * @return a day-of-week query for the week end
    */
  def weekEnd(): IntervalQuery =
    weekDays(DayOfWeek.SATURDAY.getValue, DayOfWeek.SUNDAY.getValue + 1)

  /**
    * Creates an ''IntervalQuery'' that selects exactly the specified days of
    * week. In contrast to ''weekDays()'' which operates on an interval of
    * days, this function accepts a set with days. It creates a minimum number
    * of intervals to cover all the specified days.
    *
    * @param days a set with the numeric values representing the selected days
    * @return the new interval query
    */
  def weekDaySet(days: Set[Int]): IntervalQuery = {
    @tailrec def createIntervalQueries(days: List[Int], startDay: Int, currentEnd: Int,
                                       queries: List[IntervalQuery]): List[IntervalQuery] = {
      def lastInterval(): IntervalQuery = weekDays(startDay, currentEnd + 1)

      days match {
        case d :: x =>
          if (d == currentEnd + 1) createIntervalQueries(x, startDay, d, queries)
          else createIntervalQueries(x, d, d, lastInterval() :: queries)
        case _ =>
          lastInterval() :: queries
      }
    }

    val dayList = days.toList.sorted
    val queries = dayList.headOption map (d => createIntervalQueries(dayList.tail, d, d, Nil))
    sequence(queries getOrElse List.empty)
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
  def combine(coarser: IntervalQuery, finer: IntervalQuery): IntervalQuery =
    date => processCombinedQuery(coarser, finer, date)

  /**
    * Wraps the specified interval query in a cyclic query. The resulting
    * query will not return an ''After''. If the wrapped query returns such a
    * result, the current date is set to the next cycle (based on the cycle
    * function in the result), and the query is invoked again. This should
    * yield a ''Before'' or an ''Inside'' result.
    *
    * @param wrapped the query to be wrapped
    * @return the resulting cyclic query
    */
  def cyclic(wrapped: IntervalQuery): IntervalQuery = {
    @tailrec def cycleQuery(date: LocalDateTime): IntervalQueryResult =
      wrapped(date) match {
        case After(f) => cycleQuery(f(date))
        case r => r
      }

    date => cycleQuery(date)
  }

  /*
   * TODO add support for stopping the iteration over all queries when a
   * certain result is found
   */
  /**
    * Combines a sequence of interval queries to a single one. All queries in
    * the sequence are evaluated, and the specified ''ResultSelector'' is used
    * to select the best result. If this yields ''None'', the specified
    * default result is returned.
    *
    * @param queries       the sequence of queries to be combined
    * @param selector      the ''ResultSelector''
    * @param defaultResult the default result to be returned
    * @return the sequence interval query
    */
  def sequence(queries: TraversableOnce[IntervalQuery], selector: ResultSelector =
  LongestInsideSelector, defaultResult: IntervalQueryResult = BeforeForEver): IntervalQuery =
    date =>
      selectResult(queries map (q => q(date)), selector) getOrElse defaultResult

  /**
    * Selects a query result from the specified sequence using the provided
    * ''ResultSelector''.
    *
    * @param results  the sequence of results to be processed
    * @param selector the ''ResultSelector''
    * @return the selected result
    */
  def selectResult(results: TraversableOnce[IntervalQueryResult], selector: ResultSelector):
  Option[IntervalQueryResult] = {
    val initSel: Option[IntervalQueryResult] = None
    results.foldLeft(initSel)(selector(_, _))
  }

  /**
    * Helper method that processes a current date against a coarser and a finer
    * interval query.
    *
    * @param coarser the coarser query
    * @param finer   the finer query
    * @param date    the current date
    * @return the query result
    */
  private def processCombinedQuery(coarser: IntervalQuery, finer: IntervalQuery, date:
  LocalDateTime): IntervalQueryResult =
  processCombinedQueryWithNextDate(coarser, finer, date, None)

  /**
    * Helper method that processes a combined query in a tail-recursive way.
    *
    * @param coarser     the coarser query
    * @param finer       the finer query
    * @param date        the current date
    * @param optNextDate an optional next date for an after result
    * @return the query result
    */
  @tailrec private def processCombinedQueryWithNextDate(coarser: IntervalQuery, finer:
  IntervalQuery,
                                                        date: LocalDateTime, optNextDate:
                                                        Option[LocalDateTime]):
  IntervalQueryResult = {
    /*
     * Special treatment for an inside result. Insides are not allowed if the
     * coarser query had an Inside result and the finer had an After result.
     * This is checked by this function.
     */
    def suppressInside(result: IntervalQueryResult): IntervalQueryResult =
    optNextDate match {
      case Some(d) =>
        result match {
          case Inside(_) =>
            // in this case, nextDate is the next interval start date
            Before(new LazyDate(d))
          case r => r
        }
      case None => result
    }

    coarser(date) match {
      case rb@Before(start) =>
        finer(start.value) match {
          case f: Before => f
          case _ => rb
        }

      case Inside(_) =>
        finer(date) match {
          case After(f) =>
            val nextDate = f(date)
            processCombinedQueryWithNextDate(coarser, finer, nextDate, Some(nextDate))
          case r => suppressInside(r)
        }

      case ra@After(_) => ra
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
      if (date.get(field) >= until) After { d =>
          fInc(fSet(date, date.range(field).getMaximum.toInt))
        }
      else if (date.get(field) < from) {
        Before(new LazyDate(fSet(date, from)))
      } else {
        Inside(new LazyDate(fInc(fSet(date, until - 1))))
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

  /**
    * Implements the result comparator for the ''longest Inside result''.
    *
    * @param r1 the first result
    * @param r2 the second result
    * @return the result of the comparison
    */
  private def longestInsideCompare(r1: IntervalQueryResult, r2: IntervalQueryResult): Boolean =
    r2 match {
      case After(_) => true
      case Before(d2) =>
        r1 match {
          case After(_) => false
          case Inside(_) => true
          case Before(d1) => d1.value.compareTo(d2.value) < 0
        }
      case Inside(d2) =>
        r1 match {
          case Inside(d1) => d1.value.compareTo(d2.value) > 0
          case _ => false
        }
    }

  /**
    * Implements the result comparator for the ''shortest Inside result''.
    *
    * @param r1 the first result
    * @param r2 the second result
    * @return the result of the comparison
    */
  private def shortestInsideCompare(r1: IntervalQueryResult, r2: IntervalQueryResult): Boolean =
    !longestInsideCompare(r1, r2)

  /**
    * Implements the ''longest Inside'' selector.
    *
    * @param sel     the current selection
    * @param current the current element
    * @return the new selected element
    */
  private def longestInsideSelect(sel: Option[IntervalQueryResult], current: IntervalQueryResult)
  : Option[IntervalQueryResult] =
    sel match {
      case None => Some(current)
      case s@Some(r) =>
        if (longestInsideCompare(r, current)) s else Some(current)
    }

  /**
    * Calculates a date that lies in the very far future. This date is used
    * by the default ''BeforeForEver'' result.
    *
    * @return the date in the far future
    */
  private def forEverDate(): LocalDateTime = {
    def max(field: TemporalField): Int = field.range().getMaximum.toInt

    LocalDateTime.of(max(ChronoField.YEAR), max(ChronoField.MONTH_OF_YEAR), max(ChronoField
      .DAY_OF_MONTH), max(ChronoField.HOUR_OF_DAY), max(ChronoField.MINUTE_OF_HOUR))
  }
}
