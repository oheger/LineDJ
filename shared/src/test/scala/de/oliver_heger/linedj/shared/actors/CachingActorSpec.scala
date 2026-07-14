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

package de.oliver_heger.linedj.shared.actors

import de.oliver_heger.linedj.shared.actors.CachingActorSpec.testResolverFunc
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Scheduler
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Future

object CachingActorSpec:
  /**
    * A function that generates a test value for a given test key.
    *
    * @param key the key
    * @return the value for this key
    */
  private def valueFor(key: Int): String = s"$key-value"

  /**
    * A test resolver function operating on Int keys and String values. For
    * positive keys, the function computes a test value. For negative keys, it
    * returns a failed future whose message contains the test value.
    *
    * @param key the input key
    * @return a [[Future]] with the resolved value
    */
  private def testResolverFunc(key: Int): Future[String] =
    val value = valueFor(key)
    if key >= 0 then
      Future.successful(value)
    else
      Future.failed(new IllegalArgumentException(s"Test exception: '$value'."))

  /**
    * Reads the next value from a blocking queue with a timeout.
    *
    * @param queue the queue in question
    * @tparam E the type of the queue elements
    * @return the value read from the queue
    */
  private def readQueue[E](queue: BlockingQueue[E]): E =
    queue.poll(3, TimeUnit.SECONDS)
end CachingActorSpec

/**
  * Test class for [[CachingActor]].
  */
class CachingActorSpec extends ScalaTestWithActorTestKit, AsyncFlatSpecLike, Matchers:

  import CachingActorSpec.*

  given Scheduler = testKit.scheduler

  "A caching actor" should "obtain a value from the resolver function" in :
    val key = 1
    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc))

    actor.get(key) map : value =>
      value should be(valueFor(key))

  it should "handle a failure from the resolver function" in :
    val errorKey = -17
    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc))

    recoverToExceptionIf[IllegalArgumentException]:
      actor.get(errorKey)
    .map: exception =>
      exception.getMessage should include(valueFor(errorKey))

  it should "cache the values of requested keys" in :
    val resolver = new ResolverWithQueues
    val actor = testKit.spawn(CachingActor.newInstance(resolver.resolver))

    val fut1 = actor.get(1)
    resolver.passValue("value1")
    fut1 flatMap : res1 =>
      res1 should be("value1")
      val fut2 = actor.get(1)
      val fut3 = actor.get(2)
      resolver.passValue("value2")

      for
        res2 <- fut2
        res3 <- fut3
      yield
        res2 should be("value1")
        res3 should be("value2")
        resolver.nextInput should be(1)
        resolver.nextInput should be(2)

  it should "handle multiple concurrent requests for the same key" in :
    val key = 11
    val value = "the correct result"
    val count = 16
    val resolver = new ResolverWithQueues
    val actor = testKit.spawn(CachingActor.newInstance(resolver.resolver))
    val fut1 = actor.get(key)
    resolver.nextInput should be(key)

    val futResponses = fut1 :: (2 to count).map(_ => actor.get(key)).toList
    resolver.passValue(value)
    Future.sequence(futResponses) map : responses =>
      forEvery(responses): res =>
        res should be(value)

  it should "clear the list of pending requests after receiving the value" in :
    val storedValue = new AtomicReference[String]
    val store = new CachingActor.Store[Int, String]:
      override def get(key: Int): Option[String] = Option(storedValue.get())

      override def put(key: Int, value: String): Unit = storedValue.set(value)

    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc, store))
    actor.get(5) flatMap : result =>
      result should be(valueFor(5))
      storedValue.set(null)
      actor.get(5) map : result2 =>
        result2 should be(valueFor(5))

  it should "handle a Stop command" in :
    val probeWatch = testKit.createDeadLetterProbe()
    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc))

    actor ! CachingActor.CacheCommand.Stop()

    probeWatch.expectTerminated(actor)
    succeed

  it should "support querying multiple keys" in :
    val keysToQuery = List(1, 2, 3, -4, 5, -7, 29)
    // Prepopulate the store.
    val store = CachingActor.mapStore[Int, String]
    store.put(1, valueFor(1))
    store.put(2, valueFor(2))

    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc, store))
    actor.getMultiple(keysToQuery) map : response =>
      response.resolved.size + response.failures.size should be(keysToQuery.size)
      forEvery(keysToQuery.filter(_ > 0)): key =>
        response.resolved(key) should be(valueFor(key))
      forEvery(keysToQuery.filter(_ < 0)): key =>
        response.failures(key) shouldBe a[IllegalArgumentException]
        response.failures(key).getMessage should include(valueFor(key))

  it should "handle duplicates when querying multiple keys" in :
    val KnownKey = 1
    val KeyToResolve = 11
    val knownKeyValue = new AtomicInteger
    val resolvedKeyStore = new AtomicReference[String]

    val store = new CachingActor.Store[Int, String]:
      override def get(key: Int): Option[String] =
        if key == KnownKey then
          Some(s"value-${knownKeyValue.incrementAndGet()}")
        else
          None

      override def put(key: Int, value: String): Unit =
        resolvedKeyStore.compareAndSet(null, value) shouldBe true

    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc, store))
    actor.getMultiple(List(KnownKey, KeyToResolve, KeyToResolve, KnownKey, KeyToResolve)) map : response =>
      val expectedResponse = CachingActor.MultiCacheResponse(
        resolved = Map(KnownKey -> "value-1", KeyToResolve -> valueFor(KeyToResolve)),
        failures = Map.empty
      )
      response should be(expectedResponse)

  it should "support a store with LRU logic" in :
    val Capacity = 4
    val resolver = new ResolverWithQueues
    val actor = testKit.spawn(CachingActor.newInstance(resolver.resolver, CachingActor.lruStore(Capacity)))
    val futResults = (1 to Capacity) map : index =>
      val futResult = actor.get(index)
      resolver.nextInput should be(index)
      resolver.passValue("value" + index)
      futResult

    Future.sequence(futResults) flatMap : _ =>
      actor.get(1) flatMap : res1 =>
        res1 should be("value1")
        val fut42 = actor.get(42)
        resolver.nextInput should be(42)
        resolver.passValue("special-value")
        fut42 map : res42 =>
          res42 should be("special-value")
          actor.get(2)
          resolver.passValue("value2.2")
          resolver.nextInput should be(2)

  it should "respect the parallelism limit" in :
    val Parallelism = 8
    val resolver = new ResolverWithQueues
    val probe = testKit.createTestProbe[CachingActor.MultiCacheResponse[Int, String]]()
    val actor = testKit.spawn(CachingActor.newInstance(resolver.resolver, parallelLimit = Some(Parallelism)))
    val queryKeys = 1 to 2 * Parallelism

    actor ! CachingActor.CacheCommand.GetMultiple(queryKeys, probe.ref)

    eventually(resolver.inputQueueSize should be(Parallelism))
    probe.expectNoMessage(100.millis)
    val finalQueueSize = resolver.inputQueueSize
    queryKeys.foreach(k => resolver.passValue("value" + k)) // Let the futures complete.
    finalQueueSize should be(Parallelism)

  it should "handle requests when a limit for parallelism is set" in :
    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc, parallelLimit = Some(8)))

    for
      res1 <- actor.getMultiple(1 to 10)
      res2 <- actor.get(42)
      res3 <- actor.getMultiple(11 to 24)
      res4 <- actor.getMultiple(List(1, 7, 7, -1, 30, 100))
    yield
      val expectedRes1 = (1 to 10).map(i => i -> valueFor(i)).toMap
      val expectedRes3 = (11 to 24).map(i => i -> valueFor(i)).toMap
      val expectedRes4 = Map(
        1 -> valueFor(1),
        7 -> valueFor(7),
        30 -> valueFor(30),
        100 -> valueFor(100)
      )
      res1.resolved should be(expectedRes1)
      res3.resolved should be(expectedRes3)
      res4.resolved should be(expectedRes4)
      res2 should be(valueFor(42))
      res4.failures should have size 1
      res4.failures(-1) shouldBe a[IllegalArgumentException]

  it should "handle a Stop command when parallelism is limited" in :
    val probeWatch = testKit.createDeadLetterProbe()
    val actor = testKit.spawn(CachingActor.newInstance(testResolverFunc, parallelLimit = Some(16)))

    actor ! CachingActor.CacheCommand.Stop()

    probeWatch.expectTerminated(actor)
    succeed

  /**
    * A helper class that provides a resolver function that can be monitored
    * and controlled via two blocking queues: From one queue, the keys passed
    * to the resolver function can be read. The other can be used to provide
    * the values to be returned by the resolver function. This can be used to
    * test the behavior of the caching actor when concurrent requests are
    * received.
    */
  private class ResolverWithQueues:
    /** The queue to track inputs to the resolver function. */
    private val inputQueue = new LinkedBlockingQueue[Int]

    /** The queue to define the results of the resolver function. */
    private val outputQueue = new LinkedBlockingQueue[String]

    /** The resolver function managed by this instance. */
    val resolver: CachingActor.KeyResolverFunc[Int, String] = key =>
      inputQueue.offer(key)
      Future:
        outputQueue.take()

    /**
      * Returns the next key that was passed to the resolver function.
      *
      * @return the next key passed to the resolver function
      */
    def nextInput: Int = readQueue(inputQueue)

    /**
      * Returns the current size of the input queue.
      *
      * @return the size of the input queue
      */
    def inputQueueSize: Int = inputQueue.size()

    /**
      * Provides the given value to be returned by the resolver function.
      *
      * @param value the value
      */
    def passValue(value: String): Unit =
      outputQueue.offer(value)
  end ResolverWithQueues
