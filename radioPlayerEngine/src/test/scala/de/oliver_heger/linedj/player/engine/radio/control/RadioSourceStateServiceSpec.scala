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

import akka.actor.ActorSystem
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries.hours
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{After, Before, Inside, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.interval.LazyDate
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceStateService._
import de.oliver_heger.linedj.player.engine.radio.control.ReplacementSourceSelectionService.{ReplacementSourceSelectionResult, SelectedReplacementSource}
import de.oliver_heger.linedj.player.engine.radio.{RadioPlayerConfig, RadioSource, RadioSourceConfig}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.{LocalDateTime, Month}
import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object RadioSourceStateServiceSpec {
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
                          oldState: RadioSourceState = RadioSourceStateServiceImpl.InitialState): RadioSourceState = {
    val (next, _) = updateState(s, oldState)
    next
  }

  /**
    * Creates the test configuration for the radio player.
    *
    * @return the radio player test configuration
    */
  private def createRadioPlayerConfig(): RadioPlayerConfig = {
    val playerConfig = PlayerConfig(mediaManagerActor = null, actorCreator = null)
    RadioPlayerConfig(playerConfig = playerConfig,
      maximumEvalDelay = 33.minutes,
      retryFailedReplacement = 111.seconds)
  }
}

/**
  * Test class for [[RadioSourceStateService]] and its default implementation.
  */
class RadioSourceStateServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar with AsyncTestHelper {

  import RadioSourceStateServiceSpec._

  "RadioSourceStateServiceImpl" should "define a correct initial state" in {
    import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceStateServiceImpl.InitialState._
    sourcesConfig should be(RadioSourceConfig.Empty)
    rankedSources shouldBe empty
    currentSource shouldBe empty
    replacementSource shouldBe empty
    actions shouldBe empty
    seqNo should be(0)
  }

  it should "update the state for a new configuration" in {
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
  }

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
                            seqNo: Int): Unit = {
    val evalService = mock[EvaluateIntervalsService]
    val refDate = LocalDateTime.of(2023, Month.JANUARY, 22, 21, 36, 34)
    val evalResult = mock[IntervalQueryResult]
    val ec = mock[ExecutionContext]
    val evalResponse = EvaluateIntervalsResponse(evalResult, seqNo)
    when(evalService.evaluateIntervals(sourcesConfig.exclusions(currentSource), refDate, seqNo)(ec))
      .thenReturn(Future.successful(evalResponse))
    val evalFuncResult = futureResult(evalFunc(evalService, refDate, ec))
    evalFuncResult should be(evalResponse)
  }

  it should "update the state for a new configuration if already a source is being played" in {
    val SeqNo = 17
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources(1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource), seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.initSourcesConfig(sourcesConfig), state)

    nextState.rankedSources.keySet should contain theSameElementsInOrderAs List(3, 2, 1)
    nextState.actions should have size 1
    nextState.actions.head match {
      case TriggerEvaluation(evalFunc) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo)

      case a => fail("Unexpected action: " + a)
    }
  }

  it should "update the state for a valid reevaluation" in {
    val SeqNo = 21
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources.head
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, sourcesConfig = sourcesConfig)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluateCurrentSource(SeqNo), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should have size 1
    nextState.actions.head match {
      case TriggerEvaluation(evalFunc) =>
        checkEvalFunc(sourcesConfig, currentSource, evalFunc, SeqNo)

      case a => fail("Unexpected action: " + a)
    }
  }

  it should "update the state for a stale reevaluation" in {
    val SeqNo = 23
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val currentSource = sourcesConfig.sources.head
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      seqNo = SeqNo, sourcesConfig = sourcesConfig)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluateCurrentSource(SeqNo - 1), state)

    nextState should be(state)
  }

  it should "handle an evaluation result of type After" in {
    val SeqNo = 29
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 23, 22, 8, 11)
    val evalResult = After(_ => refTime)
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      seqNo = SeqNo, actions = List(ExistingAction))
    val expectedAction = ScheduleSourceEvaluation(TestConfig.maximumEvalDelay, SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should contain theSameElementsInOrderAs List(expectedAction, ExistingAction)
  }

  it should "handle an evaluation result of type Before" in {
    val SeqNo = 33
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 23, 22, 8, 11)
    val beforeTime = refTime.plusMinutes(10)
    val evalResult = Before(new LazyDate(beforeTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(radioSource(1)),
      seqNo = SeqNo, actions = List(ExistingAction))
    val expectedAction = ScheduleSourceEvaluation(10.minutes, SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should contain theSameElementsInOrderAs List(expectedAction, ExistingAction)
  }

  it should "handle an evaluation result of type Inside" in {
    val SeqNo = 37
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val refTime = LocalDateTime.now()
    val insideTime = LocalDateTime.of(2023, Month.JANUARY, 24, 21, 36, 28)
    val evalResult = Inside(new LazyDate(insideTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo)
    val currentSource = radioSource(5)
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource),
      rankedSources = TreeMap(10 -> List(radioSource(1))),
      sourcesConfig = sourcesConfig,
      actions = List(ExistingAction),
      seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime), state)

    nextState.seqNo should be(SeqNo)
    nextState.actions should have size 2
    nextState.actions(1) should be(ExistingAction)
    nextState.actions.head match {
      case TriggerReplacementSelection(replaceFunc) =>
        val evalService = mock[EvaluateIntervalsService]
        val replaceService = mock[ReplacementSourceSelectionService]
        val actorSystem = mock[ActorSystem]
        val replaceResult = mock[ReplacementSourceSelectionResult]
        when(replaceService.selectReplacementSource(sourcesConfig, state.rankedSources, Set(currentSource),
          insideTime, SeqNo, evalService)(actorSystem)).thenReturn(Future.successful(replaceResult))
        futureResult(replaceFunc(replaceService, evalService, actorSystem)) should be(replaceResult)

      case a => fail("Unexpected action: " + a)
    }
  }

  it should "handle a stale evaluation result" in {
    val SeqNo = 39
    val refTime = LocalDateTime.now()
    val insideTime = LocalDateTime.of(2023, Month.JANUARY, 24, 22, 11, 33)
    val evalResult = Inside(new LazyDate(insideTime))
    val evalResponse = EvaluateIntervalsResponse(evalResult, SeqNo - 1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(seqNo = SeqNo,
      currentSource = Some(radioSource(4)))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime), state)

    nextState should be(state)
  }

  it should "handle an evaluation result of type After if a replacement source is active" in {
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
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedReplaceAction,
      ExistingAction)
  }

  it should "handle an evaluation result of type Before if a replacement source is active" in {
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
    val nextState = modifyState(service.evaluationResultArrived(evalResponse, refTime), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedReplaceAction,
      ExistingAction)
  }

  it should "handle a successful result of a replacement selection" in {
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
    val nextState = modifyState(service.replacementResultArrived(result, refTime), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource should be(Some(replacementSource))
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, expectedReplaceAction,
      ExistingAction)
  }

  it should "handle a failed result of a replacement selection" in {
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
    val nextState = modifyState(service.replacementResultArrived(result, refTime), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource shouldBe empty
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, ExistingAction)
  }

  it should "not send a replacement starts action if the same replacement is selected again" in {
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
    val nextState = modifyState(service.replacementResultArrived(result, refTime), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(currentSource))
    nextState.replacementSource should be(Some(replacementSource))
    nextState.actions should contain theSameElementsAs List(expectedScheduleAction, ExistingAction)
  }

  it should "ignore a stale result of a replacement selection" in {
    val SeqNo = 57
    val currentSource = radioSource(1)
    val replacementSource = radioSource(59)
    val refTime = LocalDateTime.of(2023, Month.JANUARY, 26, 22, 4, 51)
    val replaceTime = refTime.plusMinutes(2)
    val result = ReplacementSourceSelectionResult(Some(SelectedReplacementSource(replacementSource, replaceTime)),
      SeqNo - 1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(currentSource), seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.replacementResultArrived(result, refTime), state)

    nextState should be(state)
  }

  it should "set a new current source" in {
    val SeqNo = 59
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val oldSource = sourcesConfig.sources.head
    val newSource = sourcesConfig.sources(1)
    val replacementSource = sourcesConfig.sources(2)
    val ExistingAction = mock[RadioSourceStateService.StateAction]
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(oldSource),
      replacementSource = Some(replacementSource), actions = List(ExistingAction), seqNo = SeqNo,
      sourcesConfig = sourcesConfig)
    val expectedPlayAction = PlayCurrentSource(newSource, Some(replacementSource))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.setCurrentSource(newSource), state)

    nextState.seqNo should be(SeqNo + 1)
    nextState.currentSource should be(Some(newSource))
    nextState.replacementSource shouldBe empty
    nextState.actions should have size 3
    nextState.actions should contain allElementsOf List(ExistingAction, expectedPlayAction)

    nextState.actions.find(_.isInstanceOf[TriggerEvaluation]) match {
      case Some(TriggerEvaluation(evalFunc)) =>
        checkEvalFunc(sourcesConfig, newSource, evalFunc, SeqNo + 1)
      case _ => fail("No evaluation triggered.")
    }
  }

  it should "not return a play source action if the current source is not changed" in {
    val SeqNo = 61
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val source = sourcesConfig.sources(1)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(source),
      sourcesConfig = sourcesConfig, seqNo = SeqNo)

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.setCurrentSource(source), state)

    nextState.actions.find(_.isInstanceOf[PlayCurrentSource]) shouldBe empty
  }

  it should "return a play source action if the current source is not changed, but a replacement is active" in {
    val SeqNo = 67
    val sourcesConfig = RadioSourceConfigTestHelper.createSourceConfig(RadioSourceConfigTestHelper.TestSourcesQueryMap)
    val source = sourcesConfig.sources(1)
    val replacementSource = sourcesConfig.sources(2)
    val state = RadioSourceStateServiceImpl.InitialState.copy(currentSource = Some(source),
      sourcesConfig = sourcesConfig, replacementSource = Some(replacementSource), seqNo = SeqNo)
    val expPlayAction = PlayCurrentSource(source, Some(replacementSource))

    val service = new RadioSourceStateServiceImpl(TestConfig)
    val nextState = modifyState(service.setCurrentSource(source), state)

    nextState.actions should contain(expPlayAction)
  }

  it should "support reading the current actions" in {
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
  }
}
