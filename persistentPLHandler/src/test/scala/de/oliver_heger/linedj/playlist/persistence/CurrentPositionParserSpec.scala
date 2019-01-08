/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''CurrentPositionParser''.
  */
class CurrentPositionParserSpec extends FlatSpec with Matchers {
  "A CurrentPositionParser" should "parse valid position data" in {
    val data =
      """{ "index": 42, "position":20171202,
        | "time"    :   123456789
        | }
      """.stripMargin

    val pos = CurrentPositionParser parsePosition data
    pos.index should be(42)
    pos.positionOffset should be(20171202)
    pos.timeOffset should be(123456789)
  }

  it should "return defaults for properties that could not be parsed" in {
    val pos = CurrentPositionParser parsePosition ""

    pos.index should be(0)
    pos.positionOffset should be(0)
    pos.timeOffset should be(0)
  }
}
