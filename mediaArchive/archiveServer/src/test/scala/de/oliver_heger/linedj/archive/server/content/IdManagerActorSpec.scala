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

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

object IdManagerActorSpec:
  /** The ID prefix used by default for test cases. */
  private val IdPrefix = "tid"
end IdManagerActorSpec

/**
  * Test class for [[IdManagerActor]].
  */
class IdManagerActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  import IdManagerActorSpec.*

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

  "An IdManagerActor" should "generate ID values" in :
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()
    val name1 = Some("TestEntity1")
    val name2 = Some("TestEntity2")

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    actor ! IdManagerActor.QueryIdCommand.GetId(name1, probe.ref)
    actor ! IdManagerActor.QueryIdCommand.GetId(name2, probe.ref)

    val result1 = probe.expectMessageType[IdManagerActor.GetIdResponse]
    val result2 = probe.expectMessageType[IdManagerActor.GetIdResponse]
    result1.name should be(name1)
    result2.name should be(name2)
    forEvery(List(result1, result2)): result =>
      result.id should startWith(IdPrefix + "_")
    result1.id should not be result2.id

  it should "generate ID values in a case-insensitive way" in :
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()
    val name1 = Some("Test Entity")
    val name2 = Some("TEST ENTITY")
    val name3 = Some("test entity")

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    actor ! IdManagerActor.QueryIdCommand.GetId(name1, probe.ref)
    actor ! IdManagerActor.QueryIdCommand.GetId(name2, probe.ref)
    actor ! IdManagerActor.QueryIdCommand.GetId(name3, probe.ref)

    val result1 = probe.expectMessageType[IdManagerActor.GetIdResponse]
    val result2 = probe.expectMessageType[IdManagerActor.GetIdResponse]
    val result3 = probe.expectMessageType[IdManagerActor.GetIdResponse]
    result1.name should be(name1)
    result2.name should be(name2)
    result3.name should be(name3)
    result1.id should be(result2.id)
    result1.id should be(result3.id)

  it should "return an ID value for the undefined name" in :
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    actor ! IdManagerActor.QueryIdCommand.GetId(None, probe.ref)

    probe.expectMessage(IdManagerActor.GetIdResponse(None, IdPrefix + "0"))

  it should "cache calculated ID values" in :
    val hashCount = new AtomicInteger
    val hashFunc: IdManagerActor.IdCalculatorFunc = name =>
      hashCount.incrementAndGet()
      name + "_hashed"
    val name = Some("this is test input")
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix, hashFunc))
    actor ! IdManagerActor.QueryIdCommand.GetId(name, probe.ref)
    actor ! IdManagerActor.QueryIdCommand.GetId(name, probe.ref)

    val expectedResult = IdManagerActor.GetIdResponse(name, IdPrefix + "_" + name.get + "_hashed")
    probe.expectMessage(expectedResult)
    probe.expectMessage(expectedResult)
    hashCount.get() should be(1)
    