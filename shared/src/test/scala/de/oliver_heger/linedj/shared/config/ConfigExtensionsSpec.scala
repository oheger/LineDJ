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

package de.oliver_heger.linedj.shared.config

import de.oliver_heger.linedj.shared.config.ConfigExtensions.{toDuration, toDurationUnit}
import org.scalatest.TryValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
  * Test class for [[ConfigExtensions]].
  */
class ConfigExtensionsSpec extends AnyFlatSpec with Matchers with TryValues:
  "toDuration" should "convert a string with unit 'milliseconds'" in :
    val s = "100milliseconds"
    s.toDuration.success.value should be(100.milliseconds)

  it should "convert a string with unit 'millis'" in :
    val s = "100 millis"
    s.toDuration.success.value should be(100.milliseconds)

  it should "convert a string with unit 'ms'" in :
    val s = "100  ms "
    s.toDuration.success.value should be(100.milliseconds)

  it should "ignore case when selecting the unit" in :
    val s = "100Ms"
    s.toDuration.success.value should be(100.milliseconds)

  it should "return a failure for an unknown unit" in :
    val unknownUnit = "light-years"
    val s = s"100 $unknownUnit"

    val exception = s.toDuration.failure.exception
    exception.getMessage should include(unknownUnit)

  it should "return a failure for unexpected input" in :
    val invalidInput = "This is not a number with a unit."

    val exception = invalidInput.toDuration.failure.exception
    exception.getMessage should include(invalidInput)

  it should "convert a string with unit 'seconds'" in :
    val s = "60 seconds"
    s.toDuration.success.value should be(60.seconds)

  it should "convert a string with unit 'sec'" in :
    val s = "70sec"
    s.toDuration.success.value should be(70.seconds)

  it should "convert a string with unit 's'" in :
    val s = "80 s "
    s.toDuration.success.value should be(80.seconds)

  it should "convert a string with unit 'minutes'" in :
    val s = "30 minutes"
    s.toDuration.success.value should be(30.minutes)

  it should "convert a string with unit 'min'" in :
    val s = "30min"
    s.toDuration.success.value should be(30.minutes)

  it should "convert a string with unit 'hours'" in :
    val s = "12hours"
    s.toDuration.success.value should be(12.hours)

  it should "convert a string with unit 'h'" in :
    val s = "12 h"
    s.toDuration.success.value should be(12.hours)

  it should "use seconds as default unit" in :
    val s = "42"
    s.toDuration.success.value should be(42.seconds)

  "toDurationUnit" should "strip leading and trailing whitespace" in :
    val s = "   MilliSeconDs "
    s.toDurationUnit.success.value should be(ConfigExtensions.DurationUnit.MilliSeconds)

  "toDurationFromTypes" should "handle an Int value" in:
    val duration = ConfigExtensions.toDurationFromTypes(42, throw new AssertionError("Unexpected string access"))

    duration.success.value should be(42.seconds)

  it should "handle a string value with a unit" in:
    val duration = ConfigExtensions.toDurationFromTypes(
      intVal = throw new IllegalArgumentException("Not a number"),
      strVal = "17 minutes"
    )

    duration.success.value should be(17.minutes)

  it should "fail if both variants are invalid" in:
    val duration = ConfigExtensions.toDurationFromTypes(
      intVal = throw new IllegalArgumentException("Not a number"),
      strVal = throw new IllegalArgumentException("Not a string either")
    )

    duration.failure.exception shouldBe a[IllegalArgumentException]
