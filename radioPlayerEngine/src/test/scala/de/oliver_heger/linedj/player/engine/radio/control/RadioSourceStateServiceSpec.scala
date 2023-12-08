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

import de.oliver_heger.linedj.player.engine.interval.IntervalQueries.hours
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.interval.LazyDate
import de.oliver_heger.linedj.player.engine.radio.Fixtures.TestPlayerConfig
import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.config.{RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceStateService.*
import de.oliver_heger.linedj.player.engine.radio.control.ReplacementSourceSelectionService.{ReplacementSourceSelectionResult, SelectedReplacementSource}
import de.oliver_heger.linedj.test.AsyncTestHelper
import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.{LocalDateTime, Month}
import scala.collection.immutable.TreeMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object RadioSourceStateServiceSpec:
  /** The configuration used by the service under test. */
  private val TestConfig = createRadioPlayerConfig()

  /**
    * Convenience method to execute a ''State'' object to produce the updated
    * state and an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @tparam A the type of the additional result
    * @return a tuple with the updated state and the additional result
    */
  private def updateState[A](s: RadioSourceStateService.StateUpdate[A],
                             oldState: RadioSourceState = RadioSourceStateServiceImpl.InitialState):
  (RadioSourceState, A) = s(oldState)

  /**
    * Convenience method to modify a ''State'' object. This means, the state is
    * only manipulated without producing an additional result.
    *
    * @param s        the ''State''
    * @param oldState the original state
    * @return the updated state
    */
  private def modifyState(s: RadioSourceStateService.StateUpdate[Unit],
                          oldState: RadioSourceState = RadioSourceStateServiceImpl.InitialState): RadioSourceState =
    val (next, _) = updateState(s, oldState)
    next

  /**
    * Creates the test configuration for the radio player.
    *
    * @return the radio player test configuration
    */
  private def createRadioPlayerConfig(): RadioPlayerConfig =
    RadioPlayerConfig(playerConfig = TestPlayerConfig,
      maximumEvalDelay = 33.minutes,
      retryFailedReplacement = 111.seconds,
      metadataCheckTimeout = 99.seconds,
      retryFailedSource = 50.seconds,
      retryFailedSourceIncrement = 3.0,
      maxRetryFailedSource = 20.hours,
      sourceCheckTimeout = 1.minute,
      streamCacheTime = 3.seconds,
      stalledPlaybackCheck = 30.seconds)

/**
  * Test class for [[RadioSourceStateService]] and its default implementation.
  */
class RadioSourceStateServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar with AsyncTestHelper:

  import RadioSourceStateServiceSpec._

  "RadioSourceStateServiceImpl" should "define a correct initial state" in:
    import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceStateServiceImpl.InitialState._
    sourcesConfig should be(RadioSourceConfig.Empty)
    rankedSources shouldBe empty
    currentSource shouldBe empty
    replacementSource shouldBe empty
    disabledSources shouldBe empty
    actions shouldBe empty
    seqNo should be(0)

  it should "update the state for a new configuration" in:
    val sourcesMap = RadioSourceConfigTestHelper.TestSourcesQueryMap +
      (radioSource(4) -> List(hours(6, 7)))
    val rankingFunc: RadioSource => Int = src =>
      RadioSourceConfigTestHelper.rankBySourceIndex(src) % 3
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(sourcesMap, rankingFunc)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.initSourcesConfig(sourcesConfig))

    nextState.seqNo should be(1)
    nextState.sourcesConfig should be(sourcesConfig)
    nextState.rankedSources.keySet should contain theSameElementsInOrderAs List(2, 1, 0)
    nextState.rankedSources(0) should contain only radioSource(3)
    nextState.rankedSources(1) should contain theSameElementsAs List(radioSource(1), radioSource(4))
    nextState.rankedSources(2) should contain only radioSource(2)
    nextState.actions shouldBe empty

  /**
    * Helper function to test an [[EvalFunc]] returned by the state service.
    *
    * @param sourcesConfig the radio sources configuration
    * @param currentSource the current source to be evaluated
    * @param evalFunc      the eval function to check
    * @param seqNo         the current sequence number
    */
  private def checkEvalFunc(sourcesConfig: RadioSourceConfig,
                            currentSource: RadioSource,
                            evalFunc: EvalFunc,
                            seqNo: Int): Unit =
    val evalService = mock[EvaluateIntervalsService]
    val refDate = LocalDateTime.of(2023, Month.JANUARY, 22, 21, 36, 34)
    val evalResult = mock[IntervalQueryResult]
    val ec = mock[ExecutionContext]
    val evalResponse = EvaluateIntervalsResponse(evalResult, seqNo)
    when(evalService.evaluateIntervals(sourcesConfig.exclusions(currentSource), refDate, seqNo)(ec))
      .thenReturn(Future.successful(evalResponse))
    val evalFuncResult = futureResult(evalFunc(evalService, refDate, ec))
    evalFuncResult should be(evalResponse)

  it should "update the state for a new configuration if already a source is being played" in:
    val SeqNo = 17
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources(1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource), seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.initSourcesConfig(sourcesConfig), state)

    nextState.rankedSources.keySet should contain theSameElementsInOrderAs List(3, 2, 1)
    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo)

      case a => fail("Unexpected action: " + a)

  it should "update the state for a valid reevaluation" in:
    val SeqNo = 21
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources.head
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, sourcesConfig = sourcesConfig)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluateCurrentSource(SeqNo), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo)

      case a => fail("Unexpected action: " + a)

  it should "update the state for a stale reevaluation" in:
    val SeqNo = 23
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources.head
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, sourcesConfig = sourcesConfig)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluateCurrentSource(SeqNo - 1), state)

    nextState should be(state)

  /**
    * Tests handling of an evaluation result of type After.
    *
    * @param sourceChanged   flag whether the source was changed
    * @param expectedActions a list with expected state actions
    * @param seqDelta        a delta value for the sequence number
    */
  private def checkAfterEvaluationResult(sourceChanged: Boolean,
                                         expectedActions: List[RadioSourceStateService.StateAction],
                                         seqDelta: Int = 0): Unit =
    val SeqNo = 29
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 23, 22, 8, 11)
    val evalResult = After(_ => refTime)
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      seqNo = SeqNo, actions = List(ExistingAction))
    val scheduleAction = ScheduleSourceEvaluation(TestConfig.maximumEvalDelay, SeqNo + seqDelta)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged), state)

    nextState.seqNo should be(SeqNo + seqDelta)
    nextState.actions should have size expectedActions.size + 2
    nextState.actions should contain allElementsOf List(ExistingAction, scheduleAction)
    nextState.actions should contain allElementsOf expectedActions

  it should "handle an evaluation result of type After" in:
    checkAfterEvaluationResult(sourceChanged = false, Nil)

  it should "handle an evaluation result of type After for a changed source" in:
    val playAction = RadioSourceStateService.PlayCurrentSource(radioSource(1), None)
    checkAfterEvaluationResult(sourceChanged = true, List(playAction), seqDelta = 1)

  /**
    * Tests handling of an evaluation result of type Before.
    *
    * @param sourceChanged   flag whether the source was changed
    * @param expectedActions a list with expected state actions
    * @param seqDelta        a delta value for the sequence number
    */
  private def checkBeforeEvaluationResult(sourceChanged: Boolean,
                                          expectedActions: List[RadioSourceStateService.StateAction],
                                          seqDelta: Int = 0): Unit =
    val SeqNo = 33
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 23, 22, 8, 11)
    val beforeTime = refTime.plusMinutes(10)
    val evalResult = Before(new LazyDate(beforeTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      seqNo = SeqNo, actions = List(ExistingAction))
    val scheduleAction = ScheduleSourceEvaluation(10.minutes, SeqNo + seqDelta)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged), state)

    nextState.seqNo should be(SeqNo + seqDelta)
    nextState.actions should have size expectedActions.size + 2
    nextState.actions should contain allElementsOf List(scheduleAction, ExistingAction)
    nextState.actions should contain allElementsOf expectedActions

  it should "handle an evaluation result of type Before" in:
    checkBeforeEvaluationResult(sourceChanged = false, Nil)

  it should "handle an evaluation result of type Before for a changed source" in:
    val playAction = RadioSourceStateService.PlayCurrentSource(radioSource(1), None)
    checkBeforeEvaluationResult(sourceChanged = true, List(playAction), seqDelta = 1)

  it should "handle an evaluation result of type Before with a date in the far future" in:
    val SeqNo = 33
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.MARCH, 11, 21, 9, 12)
    val beforeTime = LocalDateTime.of(3089, Month.OCTOBER, 31, 19, 30, 58)
    val evalResult = Before(new LazyDate(beforeTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      seqNo = SeqNo, actions = List(ExistingAction))
    val expectedAction = ScheduleSourceEvaluation(TestConfig.maximumEvalDelay, SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should contain theSameElementsInOrderAs List(expectedAction, ExistingAction)

  it should "limit the delay for the next evaluation to the configured maximum" in:
    val SeqNo = 33
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 23, 22, 8, 11)
    val beforeTime = refTime.plusMinutes(TestConfig.maximumEvalDelay.toMinutes + 10)
    val evalResult = Before(new LazyDate(beforeTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      seqNo = SeqNo, actions = List(ExistingAction))
    val expectedAction = ScheduleSourceEvaluation(TestConfig.maximumEvalDelay, SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should contain theSameElementsInOrderAs List(expectedAction, ExistingAction)

  /**
    * Tests handling an evaluation result of type Inside.
    *
    * @param sourceChanged flag whether the source was changed
    */
  private def checkInsideEvaluationResult(sourceChanged: Boolean): Unit =
    val SeqNo = 37
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.now()
    val insideTime = LocalDateTime.of(2023, Month.JANUARY, 24, 21, 36, 28)
    val evalResult = Inside(new LazyDate(insideTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val currentSource = radioSource(5)
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val disabledSource1 = radioSource(111)
    val disabledSource2 = radioSource(222)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      rankedSources = TreeMap(10 -> List(radioSource(1))),
      sourcesConfig = sourcesConfig,
      disabledSources = Set(disabledSource1, disabledSource2),
      actions = List(ExistingAction),
      seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should have size 2
    nextState.actions(1) should be(ExistingAction)
    nextState.actions.head match
      case TriggerReplacementSelection(replaceFunc, changed) if changed == sourceChanged =>
        val evalService = mock[EvaluateIntervalsService]
        val replaceService = mock[ReplacementSourceSelectionService]
        val actorSystem = mock[ActorSystem]
        val replaceResult = mock[ReplacementSourceSelectionResult]
        when(replaceService.selectReplacementSource(sourcesConfig,
          state.rankedSources,
          Set(currentSource, disabledSource1, disabledSource2),
          insideTime,
          SeqNo,
          evalService)(actorSystem)).thenReturn(Future.successful(replaceResult))
        futureResult(replaceFunc(replaceService, evalService, actorSystem)) should be(replaceResult)

      case a => fail("Unexpected action: " + a)

  it should "handle an evaluation result of type Inside" in:
    checkInsideEvaluationResult(sourceChanged = false)

  it should "handle an evaluation result of type Inside for a changed source" in:
    checkInsideEvaluationResult(sourceChanged = true)

  it should "handle a stale evaluation result" in:
    val SeqNo = 39
    val refTime = LocalDateTime.now()
    val insideTime = LocalDateTime.of(2023, Month.JANUARY, 24, 22, 11, 33)
    val evalResult = Inside(new LazyDate(insideTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo - 1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(seqNo = SeqNo,
      currentSource = Some(radioSource(4)))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged = false), state)

    nextState should be(state)

  it should "handle an evaluation result of type After if a replacement source is active" in:
    val SeqNo = 41
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 25, 21, 40, 54)
    val evalResult = After(_ => refTime)
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val currentSource = radioSource(1)
    val replacementSource = radioSource(11)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, actions = List(ExistingAction), replacementSource = Some(replacementSource))
    val expectedScheduleAction = ScheduleSourceEvaluation(TestConfig.maximumEvalDelay, SeqNo + 1)
    val expectedReplaceAction = PlayCurrentSource(currentSource, Some(replacementSource))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedReplaceAction,
      ExistingAction)

  it should "handle an evaluation result of type Before if a replacement source is active" in:
    val SeqNo = 43
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 25, 21, 42, 44)
    val beforeTime = refTime.plusMinutes(10)
    val evalResult = Before(new LazyDate(beforeTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val currentSource = radioSource(1)
    val replacementSource = radioSource(12)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, actions = List(ExistingAction), replacementSource = Some(replacementSource))
    val expectedScheduleAction = ScheduleSourceEvaluation(10.minutes, SeqNo + 1)
    val expectedReplaceAction = PlayCurrentSource(currentSource, Some(replacementSource))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedReplaceAction,
      ExistingAction)

  it should "handle a successful result of a replacement selection" in:
    val SeqNo = 47
    val currentSource = radioSource(1)
    val replacementSource = radioSource(17)
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 25, 22, 11, 22)
    val replaceTime = refTime.plusMinutes(3)
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val result = ReplacementSourceSelectionResult(Some(SelectedReplacementSource(replacementSource, replaceTime)),
      SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, actions = List(ExistingAction))
    val expectedScheduleAction = ScheduleSourceEvaluation(3.minutes, SeqNo + 1)
    val expectedReplaceAction = StartReplacementSource(currentSource, replacementSource)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.replacementResultArrived(result, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource should be(Some(replacementSource))
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedReplaceAction,
      ExistingAction)

  it should "handle a failed result of a replacement selection" in:
    val SeqNo = 51
    val currentSource = radioSource(1)
    val replacementSource = radioSource(33)
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 26, 21, 45, 37)
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val result = ReplacementSourceSelectionResult(None, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, actions = List(ExistingAction), replacementSource = Some(replacementSource))
    val expectedScheduleAction = ScheduleSourceEvaluation(TestConfig.retryFailedReplacement, SeqNo + 1)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.replacementResultArrived(result, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource shouldBe empty
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, ExistingAction)

  it should "handle a failed result of a replacement selection for a changed source" in:
    val SeqNo = 51
    val currentSource = radioSource(1)
    val replacementSource = radioSource(33)
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 26, 21, 45, 37)
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val result = ReplacementSourceSelectionResult(None, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, actions = List(ExistingAction), replacementSource = Some(replacementSource))
    val expectedScheduleAction = ScheduleSourceEvaluation(TestConfig.retryFailedReplacement, SeqNo + 1)
    val expectedPlayAction = RadioSourceStateService.PlayCurrentSource(currentSource, Some(replacementSource))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.replacementResultArrived(result, refTime, sourceChanged = true), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource shouldBe empty
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedPlayAction, ExistingAction)

  it should "not send a replacement starts action if the same replacement is selected again" in:
    val SeqNo = 53
    val currentSource = radioSource(1)
    val replacementSource = radioSource(25)
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 26, 22, 0, 2)
    val replaceTime = refTime.plusMinutes(1)
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val result = ReplacementSourceSelectionResult(Some(SelectedReplacementSource(replacementSource, replaceTime)),
      SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, actions = List(ExistingAction), replacementSource = Some(replacementSource))
    val expectedScheduleAction = ScheduleSourceEvaluation(1.minute, SeqNo + 1)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.replacementResultArrived(result, refTime, sourceChanged = false), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource should be(Some(replacementSource))
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, ExistingAction)

  it should "ignore a stale result of a replacement selection" in:
    val SeqNo = 57
    val currentSource = radioSource(1)
    val replacementSource = radioSource(59)
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 26, 22, 4, 51)
    val replaceTime = refTime.plusMinutes(2)
    val result = ReplacementSourceSelectionResult(Some(SelectedReplacementSource(replacementSource, replaceTime)),
      SeqNo - 1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource), seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.replacementResultArrived(result, refTime, sourceChanged = false), state)

    nextState should be(state)

  it should "set a new current source" in:
    val SeqNo = 59
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val oldSource = sourcesConfig.sources.head
    val newSource = sourcesConfig.sources(1)
    val replacementSource = sourcesConfig.sources(2)
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(oldSource),
      replacementSource = Some(replacementSource), actions = List(ExistingAction), seqNo = SeqNo,
      sourcesConfig = sourcesConfig)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.setCurrentSource(newSource), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(newSource))
    nextState.replacementSource shouldBe empty
    nextState.actions should have size 3
    nextState.actions should contain(ExistingAction)

    nextState.actions should contain(ReportNewSelectedSource(newSource))
    nextState.actions.find(_.isInstanceOf[TriggerEvaluation]) match
      case Some(TriggerEvaluation(evalFunc, true)) =>
        checkEvalFunc(sourcesConfig, newSource, evalFunc, SeqNo + 1)
      case _ => fail("No evaluation triggered.")

  it should "handle setting a new source which does not change anything" in:
    val SeqNo = 61
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val source = sourcesConfig.sources(1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(source),
      sourcesConfig = sourcesConfig, seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.setCurrentSource(source), state)

    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        checkEvalFunc(sourcesConfig, source, evalFunc, SeqNo + 1)
      case a => fail("Unexpected state action: " + a)

  it should "support disabling a source" in:
    val source1 = radioSource(42)
    val source2 = radioSource(84)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val update = for
      _ <- service.disableSource(source1)
      _ <- service.disableSource(source2)
    yield ()
    val nextState = modifyState(update)

    nextState.disabledSources should contain theSameElementsAs Set(source1, source2)
    nextState.actions shouldBe empty
    nextState.seqNo should be(2)

  it should "support enabling a source again" in:
    val source1 = radioSource(42)
    val source2 = radioSource(84)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val update = for
      _ <- service.disableSource(source1)
      _ <- service.disableSource(source2)
      _ <- service.enableSource(source1)
    yield ()
    val nextState = modifyState(update)

    nextState.disabledSources should contain only source2
    nextState.actions shouldBe empty
    nextState.seqNo should be(3)

  it should "support disabling the current source" in:
    val SeqNo = 83
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources(1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource), seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.disableSource(currentSource), state)

    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        val evalService = mock[EvaluateIntervalsService]
        val refDate = LocalDateTime.of(2023, Month.MARCH, 3, 21, 36, 20)
        val expUntilDate = refDate.plusSeconds(TestConfig.maximumEvalDelay.toSeconds)
        val ec = mock[ExecutionContext]
        val evalResult = futureResult(evalFunc(evalService, refDate, ec))
        evalResult.seqNo should be(state.seqNo + 1)
        evalResult.result match
          case Inside(until) =>
            until.value should be(expUntilDate)
          case res => fail("Unexpected evaluation result: " + res)

      case a => fail("Unexpected action: " + a)

  it should "support disabling the currently played replacement source" in:
    val SeqNo = 89
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources.head
    val replacementSource = sourcesConfig.sources(1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      replacementSource = Some(replacementSource), sourcesConfig = sourcesConfig, seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.disableSource(replacementSource), state)

    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo + 1)

      case a => fail("Unexpected action: " + a)

  it should "support enabling the current source" in:
    val SeqNo = 91
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = radioSource(1)
    val replacementSource = radioSource(2)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      replacementSource = Some(replacementSource),
      sourcesConfig = sourcesConfig,
      disabledSources = Set(currentSource),
      seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.enableSource(currentSource), state)

    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo + 1)

      case a => fail("Unexpected action: " + a)

  it should "support enabling a replacement source with higher ranking than the current one" in:
    val SeqNo = 91
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = radioSource(1)
    val replacementSource = radioSource(2)
    val enabledSource = radioSource(3)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      replacementSource = Some(replacementSource),
      sourcesConfig = sourcesConfig,
      disabledSources = Set(enabledSource),
      seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.enableSource(enabledSource), state)

    nextState.actions should have size 1
    nextState.actions.head match
      case TriggerEvaluation(evalFunc, false) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo + 1)

      case a => fail("Unexpected action: " + a)

  it should "not trigger a re-evaluation when a source with an equal ranking is enabled" in:
    val SeqNo = 97
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap,
      rankingF = _ => 42)
    val enabledSource = radioSource(3)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      replacementSource = Some(radioSource(2)),
      sourcesConfig = sourcesConfig,
      disabledSources = Set(enabledSource),
      seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.enableSource(enabledSource), state)

    nextState.actions shouldBe empty

  it should "support reading the current actions" in:
    val action1 = mock[StateAction]
    val action2 = mock[StateAction]
    val action3 = mock[StateAction]
    val state = RadioSourceStateServiceImpl.InitialState.copy(sourcesConfig = mock[RadioSourceConfig],
      currentSource = Some(radioSource(1)),
      replacementSource = Some(radioSource(2)),
      actions = List(action1, action2, action3),
      seqNo = 71)
    val expNextState = state.copy(actions = List.empty)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val (nextState, actions) = updateState(service.readActions(), state)

    nextState should be(expNextState)
    actions should contain theSameElementsInOrderAs List(action3, action2, action1)
