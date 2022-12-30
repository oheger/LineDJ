/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.ui

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/**
  * Test class for [[TextTimeFunctions]].
  */
class TextTimeFunctionsSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {
  "formattedTime" should "generate a correctly formatted time string" in {
    val duration = 3700.seconds
    val func = TextTimeFunctions.formattedTime()

    val text = func(duration)

    text should be("1:01:40")
  }

  "rotateText" should "return the text unchanged if its length is below the max length" in {
    val text = "The text to rotate."
    val func = TextTimeFunctions.rotateText(text, maxLen = text.length)

    val rotatedText = func(100.seconds)

    rotatedText should be(text)
  }

  it should "rotate longer text" in {
    val rotations = Table(
      ("duration", "result"),
      (0.seconds, "012345678"),
      (1.seconds, "123456789"),
      (2.seconds, "23456789 "),
      (3.seconds, "3456789 *"),
      (4.seconds, "456789 * "),
      (5.seconds, "56789 * 0"),
      (9.seconds, "9 * 01234"),
      (11.seconds, "* 0123456"),
      (13.seconds, "012345678"),
      (15.seconds, "23456789 "),
    )
    val func = TextTimeFunctions.rotateText("0123456789", maxLen = 9)

    forAll(rotations) { (duration, result) =>
      func(duration) should be(result)
    }
  }

  it should "support a relative duration" in {
    val rotations = Table(
      ("duration", "result"),
      (3.seconds, "012345678"),
      (4.seconds, "123456789"),
      (5.seconds, "23456789 "),
      (14.seconds, "* 0123456"),
      (18.seconds, "23456789 "),
    )
    val func = TextTimeFunctions.rotateText("0123456789", maxLen = 9, relativeTo = 3.seconds)

    forAll(rotations) { (duration, result) =>
      func(duration) should be(result)
    }
  }

  it should "support an alternative separator" in {
    val rotations = Table(
      ("duration", "result"),
      (0.seconds, "012345678"),
      (1.seconds, "123456789"),
      (2.seconds, "23456789."),
      (3.seconds, "3456789.0"),
      (4.seconds, "456789.01"),
      (9.seconds, "9.0123456"),
      (11.seconds, "012345678"),
    )
    val func = TextTimeFunctions.rotateText("0123456789", maxLen = 9, separator = ".")

    forAll(rotations) { (duration, result) =>
      func(duration) should be(result)
    }
  }

  it should "support a different time unit" in {
    val rotations = Table(
      ("duration", "result"),
      (0.millis, "012345678"),
      (1.millis, "123456789"),
      (3.millis, "3456789 *"),
      (9.millis, "9 * 01234"),
      (11.millis, "* 0123456"),
      (15.millis, "23456789 "),
    )
    val func = TextTimeFunctions.rotateText("0123456789", maxLen = 9, timeUnit = TimeUnit.MILLISECONDS)

    forAll(rotations) { (duration, result) =>
      func(duration) should be(result)
    }
  }

  it should "support a scaling factor" in {
    val rotations = Table(
      ("duration", "result"),
      (0.seconds, "012345678"),
      (2.seconds, "123456789"),
      (4.seconds, "23456789 "),
      (6.seconds, "3456789 *"),
      (10.seconds, "56789 * 0"),
      (22.seconds, "* 0123456"),
      (30.seconds, "23456789 "),
    )
    val func = TextTimeFunctions.rotateText("0123456789", maxLen = 9, scale = 0.5)

    forAll(rotations) { (duration, result) =>
      func(duration) should be(result)
    }
  }

  "withSuffix" should "append a suffix to another function" in {
    val Suffix = "-suffix"
    val func = TextTimeFunctions.withSuffix(Suffix)(TextTimeFunctions.formattedTime())

    val text = func(59.seconds)

    text should be(s"0:59$Suffix")
  }
}
