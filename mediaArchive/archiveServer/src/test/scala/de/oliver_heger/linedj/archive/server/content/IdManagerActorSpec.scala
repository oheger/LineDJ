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

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Scheduler
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

object IdManagerActorSpec:
  /** The ID prefix used by default for test cases. */
  private val IdPrefix = "tid"
end IdManagerActorSpec

/**
  * Test class for [[IdManagerActor]].
  */
class IdManagerActorSpec extends ScalaTestWithActorTestKit with AsyncFlatSpecLike with Matchers:

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

    val results = List(
      probe.expectMessageType[IdManagerActor.GetIdResponse],
      probe.expectMessageType[IdManagerActor.GetIdResponse],
      probe.expectMessageType[IdManagerActor.GetIdResponse]
    )
    results.map(_.name) should contain theSameElementsAs List(name1, name2, name3)
    val ids = results.map(_.id).toSet
    ids should have size 1

  it should "return an ID value for the undefined name" in :
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix))
    actor ! IdManagerActor.QueryIdCommand.GetId(None, probe.ref)

    probe.expectMessage(IdManagerActor.GetIdResponse(None, IdPrefix + "0"))
    succeed

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

  it should "handle requests asynchronously" in :
    val idRequestQueue = new ArrayBlockingQueue[String](8, true)
    val idResultQueue = new ArrayBlockingQueue[String](8, true)
    // Use a synthetic calculator func that puts its parameters in the request queue and then waits for an item
    // in the result queue. That way it can be controlled when results are available, and which requests to the
    // actor are in processing state at the same time. The queues are configured to be fair to have a deterministic
    // order in the processing of results.
    val idFunc: IdManagerActor.IdCalculatorFunc = name =>
      idRequestQueue.offer(name)
      idResultQueue.take()

    val name1 = "test-name-1"
    val name2 = "test-name-2"
    val id1 = "id-1"
    val id2 = "id-2"
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix, idFunc))
    actor ! IdManagerActor.QueryIdCommand.GetId(Some(name1), probe.ref)
    actor ! IdManagerActor.QueryIdCommand.GetId(Some(name2), probe.ref)

    idRequestQueue.poll(3, TimeUnit.SECONDS) should be(name1)
    idRequestQueue.poll(3, TimeUnit.SECONDS) should be(name2)
    idResultQueue.offer(id1)
    idResultQueue.offer(id2)

    val expectedResults = List(
      IdManagerActor.GetIdResponse(Some(name1), IdPrefix + "_" + id1),
      IdManagerActor.GetIdResponse(Some(name2), IdPrefix + "_" + id2)
    )
    val results = List(
      probe.expectMessageType[IdManagerActor.GetIdResponse],
      probe.expectMessageType[IdManagerActor.GetIdResponse]
    )
    results should contain theSameElementsAs expectedResults

  it should "handle concurrent requests for the same name" in :
    val idRequestQueue = new ArrayBlockingQueue[String](8, true)
    val idResultQueue = new ArrayBlockingQueue[String](8, true)
    // See above.
    val idFunc: IdManagerActor.IdCalculatorFunc = name =>
      idRequestQueue.offer(name)
      idResultQueue.take()

    val name = "a-very-popular-test-name"
    val id = "popular-id"
    val probe = testKit.createTestProbe[IdManagerActor.GetIdResponse]()
    val idRequest = IdManagerActor.QueryIdCommand.GetId(Some(name), probe.ref)
    val requestCount = 16

    val actor = testKit.spawn(IdManagerActor.newInstance(IdPrefix, idFunc))
    (1 to requestCount).foreach(_ => actor ! idRequest)

    idRequestQueue.poll(3, TimeUnit.SECONDS) should be(name)
    idRequestQueue.poll(100, TimeUnit.MILLISECONDS) should be(null)
    idResultQueue.offer(id)

    val expectedResult = IdManagerActor.GetIdResponse(Some(name), IdPrefix + "_" + id)
    (1 to requestCount).foreach: _ =>
      probe.expectMessage(expectedResult)
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
