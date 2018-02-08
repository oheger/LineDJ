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
  * Test class for ''Identifiable''.
  */
class IdentifiableSpec extends FlatSpec with Matchers {
  "An Identifiable" should "provide a unique ComponentID" in {
    val id1 = new Identifiable {}
    val id2 = new Identifiable {}

    id1.componentID should not be id2.componentID
  }
}
