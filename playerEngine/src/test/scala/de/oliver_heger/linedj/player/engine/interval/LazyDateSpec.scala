/*
 * Copyright 2015-2023 The Developers Team.
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

import java.time.{LocalDateTime, Month}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''LazyDate''.
  */
class LazyDateSpec extends AnyFlatSpec with Matchers {
  "A LazyDate" should "not directly evaluate the passed in date" in {
    val creator = new DateCreator

    new LazyDate(creator.createDateAndRecord())
    creator.invocationCount should be(0)
  }

  it should "return the correct date" in {
    val creator = new DateCreator
    val lazyDate = new LazyDate(creator.createDateAndRecord())

    lazyDate.value should be(creator.createDate())
  }

  it should "call the date creation function only once" in {
    val creator = new DateCreator
    val refDate = creator.createDate()
    val lazyDate = new LazyDate(creator.createDateAndRecord())
    lazyDate.value should be(refDate)
    lazyDate.value.toString should be(refDate.toString)

    creator.invocationCount should be(1)
  }

  /**
    * A helper class that creates a date and counts the number of invocations.
    */
  private class DateCreator {
    /** A counter for date creation calls. */
    var invocationCount = 0

    /**
      * Creates a new test date.
      *
      * @return the new test date
      */
    def createDate(): LocalDateTime =
      LocalDateTime.of(2016, Month.JUNE.getValue, 8, 21, 20, 4)

    /**
      * Creates a new test date and increments the counter.
      *
      * @return the new test date
      */
    def createDateAndRecord(): LocalDateTime = {
      invocationCount += 1
      createDate()
    }
  }

}
