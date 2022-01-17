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

package de.oliver_heger.linedj.shared.archive.media

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''MediumID''.
  */
class MediumIDSpec extends AnyFlatSpec with Matchers {
  "MediumID" should "detect that it is not an undefined medium" in {
    val mid = MediumID("someUri", Some("somePath"), "someComponent")

    mid.isArchiveUndefinedMedium shouldBe false
  }

  it should "detect that it is an archive's undefined medium" in {
    val mid = MediumID("someUri", None, "someComponent")

    mid.isArchiveUndefinedMedium shouldBe true
  }

  it should "detect that the global undefined ID is not an archive's undefined medium" in {
    MediumID.UndefinedMediumID.isArchiveUndefinedMedium shouldBe false
  }
}
