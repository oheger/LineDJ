/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl.schedule

import java.time.{LocalDateTime, Month}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.player.engine.RadioSource
import de.oliver_heger.linedj.player.engine.interval.{IntervalQueries, LazyDate}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQuery}
import de.oliver_heger.linedj.player.engine.impl.schedule.EvaluateIntervalsActor
.EvaluateReplacementSources
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object EvaluateIntervalsActorSpec {
  /**
    * Generates a test radio source.
    *
    * @param idx an index
    * @return the test source with this index
    */
  private def radioSource(idx: Int): RadioSource =
    RadioSource(uri = "testRadioSource" + idx)

  /**
    * Generates a request message for multiple sources.
    *
    * @param date       the reference date
    * @param srcMap     the map with sources and their queries
    * @param exclusions a set with sources to be excluded
    * @return the request message
    */
  private def createMultiSourcesRequest(date: LocalDateTime, srcMap: Map[RadioSource,
    List[IntervalQuery]], exclusions: Set[RadioSource] = Set.empty):
  EvaluateReplacementSources =
  EvaluateIntervalsActor.EvaluateReplacementSources(srcMap,
    EvaluateIntervalsActor.EvaluateSourceResponse(Inside(new LazyDate(LocalDateTime.now())),
      EvaluateIntervalsActor.EvaluateSource(radioSource(0), date, List.empty,
        exclusions = exclusions)))
}

/**
  * Test class for ''EvaluateIntervalsActor''.
  */
class EvaluateIntervalsActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers {

  import EvaluateIntervalsActorSpec._

  def this() = this(ActorSystem("EvaluateIntervalsActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An EvaluateIntervalsActor" should "handle an EvaluateSource message" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 19, 21, 55)
    val queries = List(IntervalQueries.hours(10, 12), IntervalQueries.hours(23, 24),
      IntervalQueries.hours(19, 22), IntervalQueries.hours(21, 23))
    val actor = system.actorOf(Props[EvaluateIntervalsActor])
    val msg = EvaluateIntervalsActor.EvaluateSource(radioSource(1), date, queries)

    actor ! msg
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateSourceResponse]
    response.request should be(msg)
    response.result match {
      case Inside(until) =>
        until.value should be(LocalDateTime.of(2016, Month.JUNE, 19, 23, 0))
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "handle a source with no interval queries" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 20, 22, 22)
    val queries = List.empty[IntervalQuery]
    val actor = system.actorOf(Props[EvaluateIntervalsActor])
    val msg = EvaluateIntervalsActor.EvaluateSource(radioSource(1), date, queries)

    actor ! msg
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateSourceResponse]
    response.request should be(msg)
    response.result should be(IntervalQueries.BeforeForEver)
  }

  it should "evaluate interval queries in different threads" in {
    val threadNamesRef = new AtomicReference(Set.empty[String])
    val refDate = LocalDateTime.of(2016, Month.JUNE, 21, 22, 0)

    /*
      * Creates a fake interval query that determines from which thread it is
      * invoked. All encountered thread names are stored in a set. The result
      * produced by this query is an ''Inside'' with an ''until'' date
      * derived from the number of threads. If there are multiple threads,
      * the selected date must be modified in the minutes field.
      */
    def createQuery(): IntervalQuery = date => {
      val threadName = Thread.currentThread().getName
      var done = false
      var threadCount = 0
      do {
        val names = threadNamesRef.get()
        val updatedNames = names + threadName
        threadCount = updatedNames.size
        done = names.size == threadCount || threadNamesRef.compareAndSet(names, updatedNames)
      } while (!done)
      Inside(new LazyDate(refDate plusMinutes threadCount))
    }

    val queries = (1 to 32) map (_ => createQuery())
    val msg = EvaluateIntervalsActor.EvaluateSource(radioSource(1), refDate, queries)
    val actor = system.actorOf(Props[EvaluateIntervalsActor])

    actor ! msg
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateSourceResponse]
    response.result match {
      case Inside(until) =>
        until.value.getMinute should be > 1
      case r => fail("Unexpected result: " + r)
    }
  }

  it should "evaluate the queries of multiple radio sources" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 24, 17, 57, 1)
    val srcMap = Map(radioSource(1) -> List(IntervalQueries.hours(18, 20)),
      radioSource(2) -> List(IntervalQueries.hours(23, 24), IntervalQueries.hours(17, 19)),
      radioSource(3) -> List(IntervalQueries.hours(20, 22)))
    val request = createMultiSourcesRequest(date, srcMap)
    val actor = system.actorOf(Props[EvaluateIntervalsActor])

    actor ! request
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateReplacementSourcesResponse]
    response.request should be(request)
    val results = response.results.toMap
    results(radioSource(1)) match {
      case Before(d) => d.value should be(LocalDateTime.of(2016, Month.JUNE, 24, 18, 0))
      case r => fail("Unexpected result for source 1!")
    }
    results(radioSource(2)) match {
      case Inside(d) => d.value should be(LocalDateTime.of(2016, Month.JUNE, 24, 19, 0))
      case r => fail("Unexpected result for source 2!")
    }
    results(radioSource(3)) match {
      case Before(d) => d.value should be(LocalDateTime.of(2016, Month.JUNE, 24, 20, 0))
      case r => fail("Unexpected result for source 3!")
    }
    results should have size 3
  }

  it should "process None results for sources" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 24, 18, 23, 10)
    val srcMap = Map(radioSource(1) -> List(IntervalQueries.hours(18, 20)),
      radioSource(2) -> List.empty)
    val request = createMultiSourcesRequest(date, srcMap)
    val actor = system.actorOf(Props[EvaluateIntervalsActor])

    actor ! request
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateReplacementSourcesResponse]
    val results = response.results.toMap
    results(radioSource(2)) should be(IntervalQueries.BeforeForEver)
  }

  it should "drop the result for the current source" in {
    val date = LocalDateTime.of(2016, Month.JUNE, 24, 18, 36, 8)
    val srcMap = Map(radioSource(1) -> List(IntervalQueries.hours(18, 20)),
      radioSource(0) -> List(IntervalQueries.hours(19, 22)))
    val request = createMultiSourcesRequest(date, srcMap)
    val actor = system.actorOf(Props[EvaluateIntervalsActor])

    actor ! request
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateReplacementSourcesResponse]
    val results = response.results.toMap
    results should have size 1
    results contains radioSource(1) shouldBe true
  }

  it should "drop sources defined as exclusion sources" in {
    val date = LocalDateTime.of(2016, Month.JULY, 30, 16, 40, 37)
    val srcMap = Map(radioSource(1) -> List(IntervalQueries.hours(16, 20)),
      radioSource(0) -> List(IntervalQueries.hours(19, 22)),
      radioSource(2) -> List(IntervalQueries.hours(1, 2)),
      radioSource(8) -> List(IntervalQueries.hours(4, 5)))
    val request = createMultiSourcesRequest(date, srcMap,
      exclusions = Set(radioSource(2), radioSource(8), radioSource(11)))
    val actor = system.actorOf(Props[EvaluateIntervalsActor])

    actor ! request
    val response = expectMsgType[EvaluateIntervalsActor.EvaluateReplacementSourcesResponse]
    val results = response.results.toMap
    results should have size 1
    results contains radioSource(1) shouldBe true
  }
}
