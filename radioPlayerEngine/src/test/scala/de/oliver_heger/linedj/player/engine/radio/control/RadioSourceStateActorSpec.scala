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

import de.oliver_heger.linedj.{ActorTestKitSupport, StateTestHelper}
import de.oliver_heger.linedj.player.engine.actors.{EventTestSupport, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.radio.config.RadioSourceConfig
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.control.ReplacementSourceSelectionService.ReplacementSourceSelectionResult
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as eqArg}
import org.mockito.Mockito.{verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import scala.collection.immutable.{IndexedSeq, Seq}
import scala.concurrent.Future
import scala.concurrent.duration.*

object RadioSourceStateActorSpec:
  /** The delay for another check of the current source. */
  private val SourceCheckDelay = 5.minutes

  /**
    * Helper function to generate a test state object based on the given index.
    * For the tests, the actual content of states is irrelevant. To test that
    * states are correctly persisted and updated, it is sufficient that they
    * can be distinguished.
    *
    * @param index the index
    * @return the test state with this index
    */
  private def testState(index: Int): RadioSourceStateService.RadioSourceState =
    RadioSourceStateServiceImpl.InitialState.copy(seqNo = index)

  /**
    * Generates a sequence with the given number of unique test states.
    *
    * @param count the number of test states
    * @return a sequence with the test states
    */
  private def testStates(count: Int): IndexedSeq[RadioSourceStateService.RadioSourceState] =
    (1 to count) map testState

/**
  * Test class for [[RadioSourceStateActor]].
  */
class RadioSourceStateActorSpec extends AnyFlatSpec with Matchers with ActorTestKitSupport with MockitoSugar:

  import RadioSourceStateActorSpec.*

  "A RadioSourceStateActor" should "init the radio source configuration" in:
    val config = mock[RadioSourceConfig]
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.initSourcesConfig(config)
    }
      .stubReadActions(states(1))
      .sendCommand(RadioSourceStateActor.InitRadioSourceConfig(config))
      .expectStateUpdates(states)

  it should "set another source as current" in:
    val source = radioSource(1)
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(source)
    }.stubReadActions(states(1))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(source))
      .expectStateUpdates(states)

  it should "set a source as disabled" in:
    val source = radioSource(11)
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.disableSource(source)
    }.stubReadActions(states(1))
      .sendCommand(RadioSourceStateActor.RadioSourceDisabled(source))
      .expectStateUpdates(states)

  it should "set a source as enabled" in:
    val source = radioSource(22)
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.enableSource(source)
    }.stubReadActions(states(1))
      .sendCommand(RadioSourceStateActor.RadioSourceEnabled(source))
      .expectStateUpdates(states)

  it should "handle a state action to switch to another source" in:
    val source = radioSource(2)
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(source)
    }.stubReadActions(states(1),
        RadioSourceStateService.PlayCurrentSource(source, None),
        RadioSourceStateService.ReportNewSelectedSource(source))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(source))
      .expectSwitchToSource(source)
      .expectStateUpdates(states)
      .checkEvent { time => RadioSourceSelectedEvent(source, time) }

  it should "handle a state action to switch to another source if a replacement source ends" in:
    val source = radioSource(3)
    val replacementSource = radioSource(33)
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(source)
    }.stubReadActions(states(1), RadioSourceStateService.PlayCurrentSource(source, Some(replacementSource)))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(source))
      .expectSwitchToSource(source)
      .expectStateUpdates(states)
      .checkEvent { time => RadioSourceReplacementEndEvent(replacementSource, time) }

  it should "handle a state action to start a replacement source" in:
    val currentSource = radioSource(1)
    val replacementSource = radioSource(11)
    val states = testStates(2)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(currentSource)
    }.stubReadActions(states(1), RadioSourceStateService.StartReplacementSource(currentSource, replacementSource))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(currentSource))
      .expectSwitchToSource(replacementSource)
      .expectStateUpdates(states)
      .checkEvent { time => RadioSourceReplacementStartEvent(currentSource, replacementSource, time) }

  /**
    * Tests whether a state action to trigger an evaluation is correctly
    * handled.
    *
    * @param sourceChanged flag whether the source was changed
    */
  private def checkEvaluationStateAction(sourceChanged: Boolean): Unit =
    val source = radioSource(1)
    val states = testStates(4)
    val evalResult = mock[EvaluateIntervalsResponse]
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(source)
    }
      .stub((), states(2)) { service =>
        service.evaluationResultArrived(eqArg(evalResult), any(), eqArg(sourceChanged))
      }.stubMultiReadActions(states(1), states(3),
      RadioSourceStateService.TriggerEvaluation(helper.evalFunc(evalResult), sourceChanged))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(source))
      .expectStateUpdates(states)
      .checkTimeOfEvaluationResult()

  it should "handle a state action to start an evaluation" in:
    checkEvaluationStateAction(sourceChanged = false)

  it should "handle a state action to start an evaluation for a changed source" in:
    checkEvaluationStateAction(sourceChanged = true)

  /**
    * Tests whether a state action to search for a replacement source is
    * handled correctly.
    *
    * @param sourceChanged flag whether the source was changed
    */
  private def checkReplacementStateAction(sourceChanged: Boolean): Unit =
    val source = radioSource(1)
    val states = testStates(4)
    val replaceResult = mock[ReplacementSourceSelectionResult]
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(source)
    }
      .stub((), states(2)) { service =>
        service.replacementResultArrived(eqArg(replaceResult), any(), eqArg(sourceChanged))
      }.stubMultiReadActions(states(1), states(3),
      RadioSourceStateService.TriggerReplacementSelection(helper.replaceFunc(replaceResult), sourceChanged))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(source))
      .expectStateUpdates(states)
      .checkTimeOfReplacementResult()

  it should "handle a state action to search a replacement source" in:
    checkReplacementStateAction(sourceChanged = false)

  it should "handle a state action to search a replacement source for a changed source" in:
    checkReplacementStateAction(sourceChanged = true)

  it should "handle a state action to schedule a check for a source" in:
    val source = radioSource(1)
    val SeqNo = 42
    val states = testStates(4)
    val helper = new StateActorTestHelper

    helper.stub((), states.head) { service =>
      service.setCurrentSource(source)
    }.stub((), states(2)) { service =>
      service.evaluateCurrentSource(SeqNo)
    }.stubMultiReadActions(states(1), states(3),
      RadioSourceStateService.ScheduleSourceEvaluation(SourceCheckDelay, SeqNo))
      .sendCommand(RadioSourceStateActor.RadioSourceSelected(source))
      .handleScheduledInvocation()
      .expectStateUpdates(states)

  /**
    * A test helper class managing an actor under test and its dependencies.
    */
  private class StateActorTestHelper
    extends StateTestHelper[RadioSourceStateService.RadioSourceState, RadioSourceStateService]
      with EventTestSupport[RadioEvent] with Matchers:
    override val updateService: RadioSourceStateService = mock[RadioSourceStateService]

    /** Mock for the service to evaluate interval queries. */
    private val evalService = mock[EvaluateIntervalsService]

    /** Mock for the service to find a relacement source. */
    private val replaceService = mock[ReplacementSourceSelectionService]

    /** Test probe for the scheduler actor. */
    private val scheduleActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the actor to play a specific source. */
    private val playbackActor = testKit.createTestProbe[RadioControlProtocol.SwitchToSource]()

    /** Test probe for the event actor. */
    private val eventActor = testKit.createTestProbe[RadioEvent]()

    /** The actor to be tested. */
    private val stateActor = testKit.spawn(RadioSourceStateActor.behavior(updateService, evalService, replaceService,
      scheduleActor.ref, playbackActor.ref, eventActor.ref))

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def sendCommand(command: RadioSourceStateActor.RadioSourceStateCommand): StateActorTestHelper =
      stateActor ! command
      this

    /**
      * Stubs the mock state service to expect a ''readActions()'' operation.
      * The state actions to be returned can be specified.
      *
      * @param state   the updated state
      * @param actions the state actions to return by the service
      * @return this test helper
      */
    def stubReadActions(state: RadioSourceStateService.RadioSourceState,
                        actions: RadioSourceStateService.StateAction*): StateActorTestHelper =
      stub(actions.toList, state) { service => service.readActions() }

    /**
      * Stubs the mock state service to expect two ''readActions()''
      * operations. This is needed to test whether ''Future'' results are
      * handled correctly and passed to the update service. For the second
      * interaction with the service, no more state actions are returned.
      *
      * @param firstState  the state for the first interaction
      * @param secondState the state for the second interaction
      * @param actions     the actions to return for the first interaction
      * @return this test helper
      */
    def stubMultiReadActions(firstState: RadioSourceStateService.RadioSourceState,
                             secondState: RadioSourceStateService.RadioSourceState,
                             actions: RadioSourceStateService.StateAction*): StateActorTestHelper =
      when(updateService.readActions()).thenAnswer((_: InvocationOnMock) => createState(firstState, actions.toList))
        .thenAnswer((_: InvocationOnMock) => createState(secondState, List.empty))
      this

    /**
      * Expects that state updates according to the given sequence of test
      * states are performed. This basically tests whether states are correctly
      * persisted.
      *
      * @param states the sequence of test states
      * @return this test helper
      */
    def expectStateUpdates(states: Seq[RadioSourceStateService.RadioSourceState]): StateActorTestHelper =
      expectStateUpdate(RadioSourceStateServiceImpl.InitialState)
      states.init.foreach(expectStateUpdate)
      this

    /**
      * Expects that the given radio source is passed to the playback actor.
      *
      * @param source the radio source
      * @return this test helper
      */
    def expectSwitchToSource(source: RadioSource): StateActorTestHelper =
      playbackActor.expectMessage(RadioControlProtocol.SwitchToSource(source))
      this

    /**
      * Checks whether the given event has been published using the event
      * actor.
      *
      * @param event the expected event
      * @return this test helper
      */
    def checkEvent(event: LocalDateTime => RadioEvent): StateActorTestHelper =
      val receivedEvent = expectEvent[RadioEvent](eventActor)
      receivedEvent should be(event(receivedEvent.time))
      this

    /**
      * Returns a [[RadioSourceStateService.EvalFunc]] that checks the passed
      * in arguments and returns the given result.
      *
      * @param result the result to return
      * @return the corresponding evaluation function
      */
    def evalFunc(result: EvaluateIntervalsResponse): RadioSourceStateService.EvalFunc = (service, time, ec) => {
      service should be(evalService)
      ec should be(testKit.system.executionContext)
      assertCurrentTime(time)
      Future.successful(result)
    }

    /**
      * Checks whether the time provided for the arrival of an evaluation
      * result is close to the current system time.
      *
      * @return this test helper
      */
    def checkTimeOfEvaluationResult(): StateActorTestHelper =
      checkTimeOfResult { (service, captor) =>
        verify(service).evaluationResultArrived(any(), captor.capture(), any())
      }

    /**
      * Returns a [[RadioSourceStateService.ReplaceFunc]] that checks the
      * passed in arguments and returns the given result.
      *
      * @param result the result to return
      * @return the corresponding replace function
      */
    def replaceFunc(result: ReplacementSourceSelectionResult): RadioSourceStateService.ReplaceFunc =
      (replSvc, evalSvc, system) => {
        replSvc should be(replaceService)
        evalSvc should be(evalService)
        system should not be null
        Future.successful(result)
      }

    /**
      * Checks whether the time provided for the arrival of a replacement
      * result is close to the current system time.
      *
      * @return this test helper
      */
    def checkTimeOfReplacementResult(): StateActorTestHelper =
      checkTimeOfResult { (service, captor) =>
        verify(service).replacementResultArrived(any(), captor.capture(), any())
      }

    /**
      * Expects that a request was sent to the scheduled invocation actor.
      * Checks this request and simulates the delayed invocation.
      *
      * @return this test helper
      */
    def handleScheduledInvocation(): StateActorTestHelper =
      val command = scheduleActor.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      command.delay should be(SourceCheckDelay)
      command.invocation.send()
      this

    override protected def eventTimeExtractor: RadioEvent => LocalDateTime = _.time

    /**
      * Helper function to check an invocation of the update service with a
      * current time value. The passed in capture function must capture the
      * time parameter. It is then further evaluated.
      *
      * @param captureFunc the function to capture the time parameter
      * @return this test helper
      */
    private def checkTimeOfResult(captureFunc: (RadioSourceStateService, ArgumentCaptor[LocalDateTime]) => Unit):
    StateActorTestHelper =
      val currentTime = capture { service =>
        val captor = ArgumentCaptor.forClass(classOf[LocalDateTime])
        captureFunc(service, captor)
        captor
      }
      assertCurrentTime(currentTime)
      this
