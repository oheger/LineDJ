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

import java.time.{LocalDate, LocalDateTime, LocalTime, Month}

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside}
import org.scalatest.{FlatSpec, Matchers}

object IntervalQueriesSpec {
  /**
    * Produces a date at the current day with the specified time.
    *
    * @param hour   the hour
    * @param minute the minute
    * @param second the second
    * @param nanos  nano seconds
    * @return the date
    */
  private def todayAt(hour: Int, minute: Int, second: Int = 0, nanos: Int = 0): LocalDateTime =
    LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute, second, nanos))
}

/**
  * Test class for ''IntervalQueries''.
  */
class IntervalQueriesSpec extends FlatSpec with Matchers {

  import IntervalQueriesSpec._

  "An hour interval query" should "return a correct Before result" in {
    val date = todayAt(21, 59)
    val query = IntervalQueries.hours(22, 23)

    query(date) match {
      case Before(start) =>
        start.value should be(todayAt(22, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct After result" in {
    val date = todayAt(22, 5)
    val query = IntervalQueries.hours(14, 22)

    query(date) should be(After)
  }

  it should "return a correct Inside result if there is a next date" in {
    val date = todayAt(21, 9)
    val query = IntervalQueries.hours(21, 23)

    query(date) match {
      case Inside(until, next) =>
        until.value should be(todayAt(23, 0))
        next.get.value should be(todayAt(22, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Inside result if there is no next date" in {
    val date = todayAt(21, 31)
    val query = IntervalQueries.hours(21, 22)

    query(date) match {
      case Inside(until, next) =>
        until.value should be(todayAt(22, 0))
        next shouldBe 'empty
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Inside result if there is an overlap in until" in {
    val date = todayAt(21, 40)
    val query = IntervalQueries.hours(21, 24)

    query(date) match {
      case Inside(until, next) =>
        val expUntil = todayAt(0, 0) plusDays 1
        until.value should be(expUntil)
      case r => fail("Unexpected result: " + r)
    }
  }

  "A minute interval query" should "return a correct Before result" in {
    val date = todayAt(21, 54, 23, 5548)
    val query = IntervalQueries.minutes(55, 60)

    query(date) match {
      case Before(start) =>
        start.value should be(todayAt(21, 55))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct After result" in {
    val date = todayAt(21, 55)
    val query = IntervalQueries.minutes(30, 55)

    query(date) should be(After)
  }

  it should "return a correct Inside result" in {
    val date = todayAt(21, 57, 11, 22222)
    val query = IntervalQueries.minutes(55, 60)

    query(date) match {
      case Inside(until, next) =>
        until.value should be(todayAt(22, 0))
        next.get.value should be(todayAt(21, 58))
      case r => fail("Unexpected result: " + r)
    }
  }

  "Day intervals" should "be supported" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 11, 21, 52, 15, 1)
    val query = IntervalQueries.days(8, 14)

    query(date) match {
      case Inside(until, next) =>
        until.value should be(LocalDateTime.of(2016, Month.JUNE, 14, 0, 0))
        next.get.value should be(LocalDateTime.of(2016, Month.JUNE, 12, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  "A month interval query" should "be supported" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 11, 22, 1, 49, 22)
    val query = IntervalQueries.months(Month.MAY.getValue, Month.AUGUST.getValue)

    query(date) match {
      case Inside(until, next) =>
        until.value should be(LocalDateTime.of(2016, Month.AUGUST, 1, 0, 0))
        next.get.value should be(LocalDateTime.of(2016, Month.JULY, 1, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle increment operations correctly" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 12, 21, 53, 32, 333)
    val query = IntervalQueries.months(Month.JUNE.getValue, Month.DECEMBER.getValue + 1)

    query(date) match {
      case Inside(until, next) =>
        until.value should be(LocalDateTime.of(2017, Month.JANUARY.getValue, 1, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }
}
