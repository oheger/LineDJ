/*
 * Copyright 2015-2023 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.*
import de.oliver_heger.linedj.player.engine.interval.LazyDate
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.control.ReplacementSourceSelectionService.SelectedReplacementSource
import de.oliver_heger.linedj.test.AsyncTestHelper
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.{LocalDateTime, Month}
import scala.collection.immutable.{Seq, SortedMap, TreeMap}
import scala.concurrent.Future

object ReplacementSourceSelectionServiceSpec:
  /** A reference date used for interval queries. */
  private val ReferenceDate = LocalDateTime.of(2023, Month.JANUARY, 16, 21, 57, 59)

  /** The sequence number used by tests. */
  private val SeqNo = 42

  /**
    * Convenience function to create a sorted map from a single radio source.
    *
    * @param source the radio source
    * @return the sorted map containing only this source
    */
  private def singleSortedSource(source: RadioSource): SortedMap[Int, Seq[RadioSource]] = TreeMap(1 -> List(source))

/**
  * Test class for [[ReplacementSourceSelectionService]].
  */
class ReplacementSourceSelectionServiceSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper:
  def this() = this(ActorSystem("ReplacementSourceSelectionService"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import ReplacementSourceSelectionServiceSpec._
  import system.dispatcher

  "ReplacementSourceSelectionServiceImpl" should "return None if no sources are available" in:
    val sourcesConfig = mock[RadioSourceConfig]
    val helper = new SelectionServiceTestHelper

    val result = helper.callSelectionService(sourcesConfig, SortedMap.empty)

    result shouldBe empty

  it should "select an available source that can be currently played" in:
    val helper = new SelectionServiceTestHelper
    val source = helper.createSourceWithQueries(1, After(_.plusHours(1)))
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(source))

    val result = helper.callSelectionService(sourcesConfig, singleSortedSource(source._1))

    result should be(Some(SelectedReplacementSource(source._1, ReferenceDate)))

  it should "filter out sources that cannot be played" in:
    val helper = new SelectionServiceTestHelper
    val source = helper.createSourceWithQueries(1, Inside(new LazyDate(ReferenceDate.plusSeconds(1))))
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(source))

    val result = helper.callSelectionService(sourcesConfig, singleSortedSource(source._1))

    result shouldBe empty

  it should "select a source that reaches a forbidden interval later" in:
    val beforeDate = ReferenceDate.minusSeconds(1)
    val helper = new SelectionServiceTestHelper
    val source = helper.createSourceWithQueries(1, Before(new LazyDate(beforeDate)))
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(source))

    val result = helper.callSelectionService(sourcesConfig, singleSortedSource(source._1))

    result should be(Some(SelectedReplacementSource(source._1, beforeDate)))

  it should "select a source that leaves a forbidden interval earlier" in:
    val helper = new SelectionServiceTestHelper
    val source = helper.createSourceWithQueries(1, Inside(new LazyDate(ReferenceDate.minusSeconds(1))))
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(source))

    val result = helper.callSelectionService(sourcesConfig, singleSortedSource(source._1))

    result should be(Some(SelectedReplacementSource(source._1, ReferenceDate)))

  it should "select a random source of same ranking" in:
    val queryResult = After(_ => ReferenceDate)
    val Attempts = 32
    val helper = new SelectionServiceTestHelper
    val sources = (1 to 16) map { idx => helper.createSourceWithQueries(idx, queryResult) }
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(sources: _*))
    val rankedSources = TreeMap(1 -> sourcesConfig.sources)

    val selectedSources = (1 to Attempts).foldLeft(Set.empty[RadioSource]) { (set, _) =>
      val result = helper.callSelectionService(sourcesConfig, rankedSources)
      result should not be empty
      set + result.get.source
    }

    selectedSources.size should be > 1

  it should "select a source with highest ranking" in:
    val queryResult = After(_ => ReferenceDate)
    val Attempts = 32
    val helper = new SelectionServiceTestHelper
    val highlyRankedSource = helper.createSourceWithQueries(1, queryResult)
    val otherSources = (2 to 16) map { idx => helper.createSourceWithQueries(idx, queryResult) }
    val allSources = highlyRankedSource :: otherSources.toList
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(allSources: _*))
    val rankedSources = TreeMap(0 -> List(highlyRankedSource._1),
      1 -> otherSources.map(_._1))
    val expectedResult = Some(SelectedReplacementSource(highlyRankedSource._1, ReferenceDate))

    (1 to Attempts) foreach { _ =>
      helper.callSelectionService(sourcesConfig, rankedSources) should be(expectedResult)
    }

  /**
    * Tests whether a source with a lower ranking that can be played until the
    * reference date is preferred over a partial replacement with a higher
    * ranking.
    *
    * @param queryResult the query result for the replacement source
    */
  private def checkFullReplacementIsPreferred(queryResult: IntervalQueryResult): Unit =
    val helper = new SelectionServiceTestHelper
    val partialReplacement = helper.createSourceWithQueries(1, Before(new LazyDate(ReferenceDate.minusSeconds(1))))
    val fullReplacement = helper.createSourceWithQueries(2, queryResult)
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(partialReplacement, fullReplacement))
    val rankedSources = TreeMap(1 -> List(partialReplacement._1), 2 -> List(fullReplacement._1))

    val result = helper.callSelectionService(sourcesConfig, rankedSources)

    result should be(Some(SelectedReplacementSource(fullReplacement._1, ReferenceDate)))

  it should "select a full replacement source with an After result over a higher ranked partial replacement" in:
    checkFullReplacementIsPreferred(After(_ => ReferenceDate))

  it should "select a full replacement source with a Before result over a higher ranked partial replacement" in:
    checkFullReplacementIsPreferred(Before(new LazyDate(ReferenceDate.plusSeconds(1))))

  it should "select a full replacement source with equal Before result over a higher ranked partial replacement" in:
    checkFullReplacementIsPreferred(Before(new LazyDate(ReferenceDate)))

  it should "allow the exclusion of specific sources" in:
    val queryResult = After(_ => ReferenceDate)
    val helper = new SelectionServiceTestHelper
    val excludedSource = helper.createSourceWithQueries(1, queryResult)
    val remainingSource = helper.createSourceWithQueries(2, queryResult)
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(Map(excludedSource, remainingSource))
    val rankedSources = TreeMap(0 -> List(excludedSource._1),
      1 -> List(remainingSource._1))

    val result = helper.callSelectionService(sourcesConfig, rankedSources, Set(excludedSource._1))

    result should be(Some(SelectedReplacementSource(remainingSource._1, ReferenceDate)))

  /**
    * A test helper class managing the dependencies of the test service.
    */
  private class SelectionServiceTestHelper:
    /** A mock for the service to evaluate interval queries. */
    private val evaluateService = mock[EvaluateIntervalsService]

    /**
      * Returns a tuple with a radio source and a sequence of interval queries.
      * The managed mock for the evaluation service is prepared to return the
      * given query result when it is asked to evaluate the interval queries
      * for this source.
      *
      * @param idx         the index of the radio source
      * @param queryResult the query result to return for this source
      * @return a tuple with the source and its interval queries
      */
    def createSourceWithQueries(idx: Int, queryResult: IntervalQueryResult): (RadioSource, Seq[IntervalQuery]) =
      val source = radioSource(idx)
      val queries = mock[Seq[IntervalQuery]]
      when(evaluateService.evaluateIntervals(queries, ReferenceDate, 0))
        .thenReturn(Future.successful(EvaluateIntervalsResponse(queryResult, 0)))
      source -> queries

    /**
      * Invokes the selection service under test with the given parameters.
      *
      * @param sourcesConfig   the configuration with radio sources
      * @param rankedSources   the sources ordered by their rankings
      * @param excludedSources the set of sources to exclude
      * @return the result returned by the selection service
      */
    def callSelectionService(sourcesConfig: RadioSourceConfig,
                             rankedSources: SortedMap[Int, Seq[RadioSource]],
                             excludedSources: Set[RadioSource] = Set.empty): Option[SelectedReplacementSource] =
      val result = futureResult(ReplacementSourceSelectionServiceImpl.selectReplacementSource(sourcesConfig,
        rankedSources, excludedSources, ReferenceDate, SeqNo, evaluateService))
      result.seqNo should be(SeqNo)
      result.selectedSource
