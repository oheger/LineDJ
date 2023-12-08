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

package de.oliver_heger.linedj.player.engine.radio.control

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDateTime, Month}
import scala.concurrent.duration._

/**
  * Test class for the package object of the control package.
  */
class PackageObjectSpec extends AnyFlatSpec with Matchers:
  "durationBetween" should "return the difference between two times" in:
    val startTime = LocalDateTime.of(2023, Month.APRIL, 9, 21, 6, 15)
    val DeltaSec = 11783

    val result = durationBetween(startTime, startTime.plusSeconds(DeltaSec), 1.minute)

    result should be(DeltaSec.seconds)

  it should "handle large differences correctly" in:
    val MaxDuration = 23.hours
    val startTime = LocalDateTime.of(2023, Month.APRIL, 9, 21, 9, 34)

    val result = durationBetween(startTime, startTime.plusYears(777), MaxDuration)

    result should be(MaxDuration)
