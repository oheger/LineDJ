/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archive.server.content

import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Test class for [[IdManagerActor]].
  */
class IdManagerActorSpec extends AnyFlatSpec with Matchers:
  "HashIdCalculatorFunc" should "compute the same ID value for the same input" in :
    val input = "The name of an entity, for which an ID is to be computed"

    val id1 = IdManagerActor.HashIdCalculatorFunc(input)
    val id2 = IdManagerActor.HashIdCalculatorFunc(input)

    id1 should be(id2)

  it should "produce different ID values for different input" in :
    val id1 = IdManagerActor.HashIdCalculatorFunc("Name of entity1")
    val id2 = IdManagerActor.HashIdCalculatorFunc("Name of entity2")

    id1 should not be id2

  it should "produce output consisting only of a limited character set" in :
    val allowedCharacters = "0123456789abcdef"
    val input = "The quick brown fox jumps over the lazy dog - 321987456, *#?%$§!"

    val id = IdManagerActor.HashIdCalculatorFunc(input)

    forEvery(id): c =>
      allowedCharacters.contains(c) shouldBe true
