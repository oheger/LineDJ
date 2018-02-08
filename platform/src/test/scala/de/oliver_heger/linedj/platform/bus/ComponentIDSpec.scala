/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.platform.bus

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''ComponentID''.
  */
class ComponentIDSpec extends FlatSpec with Matchers {
  "A ComponentID" should "be equal to itself" in {
    val id = ComponentID()

    id should be(id)
  }

  it should "not be equal to other instances" in {
    val Count = 128
    val ids = (1 to Count).map(_ => ComponentID()).toSet

    ids should have size Count
  }

  it should "generate different hash codes" in {
    val Count = 128
    val ids = (1 to Count).map(_ => ComponentID().hashCode()).toSet

    ids.size should be > 1
  }

  it should "have a toString method returning a numeric ID" in {
    val reg = """ComponentID \[-?\d+\]""".r
    val id = ComponentID()

    id.toString match {
      case reg(_*) =>
      case s => fail("Invalid string representation: " + s)
    }
  }

  it should "have different string representations for different instances" in {
    val Count = 128
    val ids = (1 to Count).map(_ => ComponentID().toString()).toSet

    ids should have size Count
  }
}
