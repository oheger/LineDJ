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

package de.oliver_heger.linedj.player.engine.radio

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''RadioSource''.
  */
class RadioSourceSpec extends AnyFlatSpec with Matchers:
  "A RadioSource" should "provide a dummy ranking function" in:
    val source1 = RadioSource("source1")
    val source2 = RadioSource("source2")

    RadioSource.NoRanking(source1) should be(0)
    RadioSource.NoRanking(source2) should be(0)

  it should "provide a dummy exclusion function" in:
    val source1 = RadioSource("source1")
    val source2 = RadioSource("source2")

    RadioSource.NoExclusions(source1) shouldBe empty
    RadioSource.NoExclusions(source2) shouldBe empty
