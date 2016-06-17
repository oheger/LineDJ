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

import java.time._

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

    query(date) match {
      case After(f) =>
        f(date) should be(todayAt(0, 0) plusDays 1)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Inside result" in {
    val date = todayAt(21, 9)
    val query = IntervalQueries.hours(21, 23)

    query(date) match {
      case Inside(until) =>
        until.value should be(todayAt(23, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Inside result if there is an overlap in until" in {
    val date = todayAt(21, 40)
    val query = IntervalQueries.hours(21, 24)

    query(date) match {
      case Inside(until) =>
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

    query(date) match {
      case After(f) =>
        f(date) should be(todayAt(22, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Inside result" in {
    val date = todayAt(21, 57, 11, 22222)
    val query = IntervalQueries.minutes(55, 60)

    query(date) match {
      case Inside(until) =>
        until.value should be(todayAt(22, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  "Day interval queries" should "be supported" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 11, 21, 52, 15, 1)
    val query = IntervalQueries.days(8, 14)

    query(date) match {
      case Inside(until) =>
        until.value should be(LocalDateTime.of(2016, Month.JUNE, 14, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  "Month interval queries" should "be supported" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 11, 22, 1, 49, 22)
    val query = IntervalQueries.months(Month.MAY.getValue, Month.AUGUST.getValue)

    query(date) match {
      case Inside(until) =>
        until.value should be(LocalDateTime.of(2016, Month.AUGUST, 1, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle increment operations correctly" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 12, 21, 53, 32, 333)
    val query = IntervalQueries.months(Month.JUNE.getValue, Month.DECEMBER.getValue + 1)

    query(date) match {
      case Inside(until) =>
        until.value should be(LocalDateTime.of(2017, Month.JANUARY.getValue, 1, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  "A combined query" should "return a correct Before result" in {
    val date = todayAt(20, 26, 10, 5)
    val coarser = IntervalQueries.hours(21, 23)
    val finer = IntervalQueries.minutes(30, 35)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case Before(start) =>
        start.value should be(todayAt(21, 30))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "take the finer query into account when returning a Before result" in {
    val date = todayAt(22, 36, 8, 1)
    val coarser = IntervalQueries.hours(23, 24)
    val finer = IntervalQueries.minutes(0, 10)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case Before(start) =>
        start.value should be(todayAt(23, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return the After result of the coarser query" in {
    val date = todayAt(21, 49, 22, 11)
    val coarser = IntervalQueries.hours(18, 21)
    val finer = IntervalQueries.minutes(30, 45)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case After(f) =>
        f(date) should be(todayAt(0, 0) plusDays 1)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return an After result if the finer query is After and no cycle is possible" in {
    val date = todayAt(22, 9, 44, 111)
    val coarser = IntervalQueries.hours(20, 23)
    val finer = IntervalQueries.minutes(0, 8)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case After(f) =>
        f(date) should be(todayAt(0, 0) plusDays 1)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "switch to the next possible date if the finer query is After" in {
    val date = todayAt(21, 56, 1, 2)
    val coarser = IntervalQueries.hours(21, 23)
    val finer = IntervalQueries.minutes(30, 45)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case Before(start) =>
        start.value should be(todayAt(22, 30))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return the Before result of finer if coarser is Inside" in {
    val date = todayAt(22, 1, 8, 4)
    val coarser = IntervalQueries.hours(22, 23)
    val finer = IntervalQueries.minutes(5, 15)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case Before(start) =>
        start.value should be(todayAt(22, 5))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return the Inside result of finer if coarser is Inside" in {
    val date = todayAt(22, 4, 12, 18)
    val coarser = IntervalQueries.hours(22, 23)
    val finer = IntervalQueries.minutes(4, 8)
    val combined = IntervalQueries.combine(coarser, finer)

    combined(date) match {
      case Inside(until) =>
        until.value should be(todayAt(22, 8))
      case r => fail("Unexpected result: " + r)
    }
  }

  "A weekDay query" should "return a correct After result" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 15, 21, 41)
    val query = IntervalQueries.weekDays(DayOfWeek.MONDAY.getValue, DayOfWeek.WEDNESDAY.getValue)

    query(date) match {
      case After(f) =>
        f(date) should be(LocalDateTime.of(2016, Month.JUNE, 20, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Before result" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 15, 21, 45)
    val query = IntervalQueries.weekDays(DayOfWeek.FRIDAY.getValue, DayOfWeek.SUNDAY.getValue)

    query(date) match {
      case Before(start) =>
        start.value should be(LocalDateTime.of(2016, Month.JUNE, 17, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "return a correct Inside result" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 15, 21, 52)
    val query = IntervalQueries.weekDays(DayOfWeek.WEDNESDAY.getValue, DayOfWeek.FRIDAY.getValue)

    query(date) match {
      case Inside(until) =>
        until.value should be(LocalDateTime.of(2016, Month.JUNE, 17, 0, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  "A cyclic query" should "return a non-After result from the wrapped query" in {
    val date = todayAt(21, 13, 11, 8)
    val wrapped = IntervalQueries.hours(21, 22)
    val cyclic = IntervalQueries.cyclic(wrapped)

    cyclic(date) match {
      case Inside(until) =>
        until.value should be(todayAt(22, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "cycle the current date for an After result" in {
    val date = todayAt(21, 20, 25, 11)
    val wrapped = IntervalQueries.hours(18, 21)
    val cyclic = IntervalQueries.cyclic(wrapped)

    cyclic(date) match {
      case Before(start) =>
        start.value should be(todayAt(18, 0) plusDays 1)
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "cycle correctly over multiple units in the inner unit" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 17, 21, 26, 2)
    val daysQuery = IntervalQueries.days(10, 18)
    val minutesQuery = IntervalQueries.minutes(10, 20)
    val query = IntervalQueries.cyclic(IntervalQueries.combine(daysQuery, minutesQuery))

    query(date) match {
      case Before(start) =>
        start.value should be(LocalDateTime.of(2016, Month.JUNE, 17, 22, 10))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "cycle correctly over multiple units in the outer unit" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 17, 21, 34, 8, 42)
    val daysQuery = IntervalQueries.days(10, 17)
    val minutesQuery = IntervalQueries.minutes(1, 12)
    val query = IntervalQueries.cyclic(IntervalQueries.combine(daysQuery, minutesQuery))

    query(date) match {
      case Before(start) =>
        start.value should be(LocalDateTime.of(2016, Month.JULY, 10, 0, 1))
      case r => fail("Unexpected result: " + r)
    }
  }

  "LongestInside result comparator" should "return true for After results" in {
    val r1 = After(identity[LocalDateTime])
    val r2 = After(d => d.plusDays(1))

    val comp = IntervalQueries.LongestInside
    comp(r1, r2) shouldBe true
    comp(r2, r1) shouldBe true
  }

  it should "compare a Before with an After" in {
    val r1 = After(identity[LocalDateTime])
    val r2 = Before(new LazyDate(todayAt(22, 12)))

    IntervalQueries.LongestInside(r1, r2) shouldBe false
    IntervalQueries.LongestInside(r2, r1) shouldBe true
  }

  it should "compare an Inside with an After" in {
    val r1 = After(identity[LocalDateTime])
    val r2 = Inside(new LazyDate(todayAt(22, 17)))

    IntervalQueries.LongestInside(r1, r2) shouldBe false
    IntervalQueries.LongestInside(r2, r1) shouldBe true
  }

  it should "compare an Inside with a Before" in {
    val r1 = Before(new LazyDate(todayAt(22, 18, 10)))
    val r2 = Inside(new LazyDate(todayAt(22, 19, 1)))

    IntervalQueries.LongestInside(r1, r2) shouldBe false
    IntervalQueries.LongestInside(r2, r1) shouldBe true
  }

  it should "compare two Before results" in {
    val r1 = Before(new LazyDate(todayAt(22, 18, 10)))
    val r2 = Before(new LazyDate(todayAt(22, 23, 1)))

    IntervalQueries.LongestInside(r1, r2) shouldBe true
    IntervalQueries.LongestInside(r2, r1) shouldBe false
  }

  it should "compare two Inside results" in {
    val r1 = Inside(new LazyDate(todayAt(22, 26, 22)))
    val r2 = Inside(new LazyDate(todayAt(22, 19, 1)))

    IntervalQueries.LongestInside(r1, r2) shouldBe true
    IntervalQueries.LongestInside(r2, r1) shouldBe false
  }

  it should "sort a list of results" in {
    val r1 = After(identity[LocalDateTime])
    val r2 = Before(new LazyDate(LocalDateTime.of(2016, Month.JUNE, 18, 20, 59)))
    val r3 = Inside(new LazyDate(todayAt(21, 0)))
    val r4 = After(identity[LocalDateTime])
    val r5 = Before(new LazyDate(todayAt(22, 1)))
    val r6 = Inside(new LazyDate(todayAt(23, 59)))
    val r7 = Inside(new LazyDate(LocalDateTime.of(2016, Month.JUNE, 20, 1, 28)))
    val inputList = List(r1, r2, r3, r4, r5, r6, r7)

    val sortedList = inputList.sortWith(IntervalQueries.LongestInside)
    sortedList.take(5) should be(List(r7, r6, r3, r2, r5))
    sortedList.drop(5).forall {
      case After(_) => true
      case _ => false
    } shouldBe true
  }
}
