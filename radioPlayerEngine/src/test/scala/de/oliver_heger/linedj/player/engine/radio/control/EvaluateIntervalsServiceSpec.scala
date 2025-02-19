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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Inside, IntervalQuery}
import de.oliver_heger.linedj.player.engine.interval.{IntervalQueries, LazyDate}
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.{LocalDateTime, Month}
import java.util.concurrent.atomic.AtomicReference

/**
  * Test class for [[EvaluateIntervalsService]].
  */
class EvaluateIntervalsServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper:
  def this() = this(ActorSystem("EvaluateIntervalsServiceSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import system.dispatcher

  "EvaluateIntervalsServiceImpl" should "handle an empty sequence of interval queries" in:
    val date = LocalDateTime.of(2023, Month.JANUARY, 15, 21, 35)
    val queries = List.empty[IntervalQuery]
    val SeqNo = 1

    val result = futureResult(EvaluateIntervalsServiceImpl.evaluateIntervals(queries, date, SeqNo))

    result should be(EvaluateIntervalsResponse(IntervalQueries.BeforeForEver, SeqNo))

  it should "correctly evaluate the provided queries" in:
    val date = LocalDateTime.of(2023, Month.JANUARY, 15, 21, 39)
    val queries = List(IntervalQueries.hours(10, 12), IntervalQueries.hours(23, 24),
      IntervalQueries.hours(19, 22), IntervalQueries.hours(21, 23))
    val SeqNo = 42

    val result = futureResult(EvaluateIntervalsServiceImpl.evaluateIntervals(queries, date, SeqNo))

    result match
      case EvaluateIntervalsResponse(Inside(since, until), seqNo) if seqNo == SeqNo =>
        since.value should be(LocalDateTime.of(2023, Month.JANUARY, 15, 21, 0))
        until.value should be(LocalDateTime.of(2023, Month.JANUARY, 15, 23, 0))
      case r => fail("Unexpected result: " + r)

  it should "evaluate interval queries in different threads" in:
    val threadNamesRef = new AtomicReference(Set.empty[String])
    val refDate = LocalDateTime.of(2023, Month.JANUARY, 15, 22, 0)
    val SeqNo = 111

    /*
      * Creates a fake interval query that determines from which thread it is
      * invoked. All encountered thread names are stored in a set. The result
      * produced by this query is an ''Inside'' with an ''until'' date
      * derived from the number of threads. If there are multiple threads,
      * the selected date must be modified in the minutes field.
      */
    def createQuery(): IntervalQuery = _ => {
      val threadName = Thread.currentThread().getName
      Thread.sleep(50)

      var done = false
      var threadCount = 0
      while !done do
        val names = threadNamesRef.get()
        val updatedNames = names + threadName
        threadCount = updatedNames.size
        done = names.size == threadCount || threadNamesRef.compareAndSet(names, updatedNames)
      Inside(new LazyDate(refDate), new LazyDate(refDate plusMinutes threadCount))
    }

    val queries = (1 to 32) map (_ => createQuery())

    val result = futureResult(EvaluateIntervalsServiceImpl.evaluateIntervals(queries, refDate, SeqNo))

    result match
      case EvaluateIntervalsResponse(Inside(_, until), SeqNo) =>
        until.value.getMinute should be > 1
      case r => fail("Unexpected result: " + r)
