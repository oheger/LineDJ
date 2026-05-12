/*
 * Copyright 2015-2026 The Developers Team.
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

import de.oliver_heger.linedj.archive.server.content.IdManagerActor.getIds
import de.oliver_heger.linedj.shared.actors.CachingActor
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Scheduler
import org.scalatest.Inspectors.forEvery
import org.scalatest.TryValues
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.concurrent.Future
import scala.util.Success

object IdManagerActorSpec:
  /** The ID prefix used by default for test cases. */
  private val IdPrefix = "tid"
end IdManagerActorSpec

/**
  * Test class for [[IdManagerActor]].
  */
class IdManagerActorSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers with TryValues:

  import IdManagerActorSpec.*

  given Scheduler = testKit.scheduler

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
    succeed

  "IdManagerActor.GetId" should "generate ID values" in :
    val name1 = Some("TestEntity1")
    val name2 = Some("TestEntity2")

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    for
      result1 <- actor.get(name1)
      result2 <- actor.get(name2)
    yield
      forEvery(List(result1, result2)): result =>
        result should startWith(IdPrefix + "_")
      result1 should not be result2

  it should "generate ID values in a case-insensitive way" in :
    val name1 = Some("Test Entity")
    val name2 = Some("TEST ENTITY")
    val name3 = Some("test entity")

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    val futResults = Future.sequence(List(name1, name2, name3).map(actor.get(_)))

    futResults map : results =>
      val ids = results.toSet
      ids should have size 1

  it should "return an ID value for the undefined name" in :
    val probe = testKit.createTestProbe[CachingActor.CacheResponse[IdManagerActor.EntityName, String]]()

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    actor ! CachingActor.CacheCommand.Get(None, probe.ref)

    probe.expectMessage(CachingActor.CacheResponse(None, Success(IdPrefix + "0")))
    succeed

  "IdManagerActor.GetIds" should "handle requests for multiple entities" in :
    val idFunc: IdManagerActor.IdCalculatorFunc = name => s"id for $name"
    val names = List(Some("foo"), Some("bar"), Some("baz"), Some("oneMore"), Some("andAnother"))
    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix, idFunc))

    actor.getIds(names) map : result =>
      result.ids should have size names.size
      forEvery(names): name =>
        result.ids(name) should be(IdPrefix + "_" + idFunc(name.get.toLowerCase(Locale.ROOT)))
      succeed

  it should "handle duplicates" in :
    val name = Some("an entity")
    val names = Array.fill(17)(name)
    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))

    actor.getIds(names) map : result =>
      result.ids.keySet should contain only name

  it should "handle the empty entity name" in :
    val name = Some("a defined entity name")
    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))

    actor.getIds(List(name, None)).map: result =>
      result.ids.keySet should contain theSameElementsAs List(name, None)
      result.ids(None) should be(IdPrefix + "0")
