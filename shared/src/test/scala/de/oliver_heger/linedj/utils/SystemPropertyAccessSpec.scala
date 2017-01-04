/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.utils

import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''SystemPropertyAccess''.
  */
class SystemPropertyAccessSpec extends FlatSpec with Matchers {
  "A SystemPropertyAccess" should "support querying system properties" in {
    val access = new SystemPropertyAccess {}

    access.getSystemProperty("user.home").get should be(System.getProperty("user.home"))
  }

  it should "return None for an undefined system property" in {
    val access = new SystemPropertyAccess {}

    access getSystemProperty "an undefined property" should be(None)
  }
}
