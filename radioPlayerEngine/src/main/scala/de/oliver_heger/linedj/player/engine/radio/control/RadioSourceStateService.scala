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
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside}
import de.oliver_heger.linedj.player.engine.interval.LazyDate
import de.oliver_heger.linedj.player.engine.radio.control.EvaluateIntervalsService.EvaluateIntervalsResponse
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceStateService._
import de.oliver_heger.linedj.player.engine.radio.control.ReplacementSourceSelectionService.ReplacementSourceSelectionResult
import de.oliver_heger.linedj.player.engine.radio.{RadioPlayerConfig, RadioSource, RadioSourceConfig}
import scalaz.State
import scalaz.State._

import java.time.LocalDateTime
import scala.collection.immutable.{SortedMap, TreeMap}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object RadioSourceStateService {
  /**
    * A trait describing an action that needs to be performed after an update
    * of the radio sources state. Concrete implementations describe how the
    * radio player engine needs to be updated to match the new state.
    */
  sealed trait StateAction

  /**
    * Type alias for a function that invokes the [[EvaluateIntervalsService]]
    * to do an evaluation of a specific radio source. The function expects a
    * service reference and the current time. It returns the ''Future'' with
    * the response of the service.
    */
  type EvalFunc = (EvaluateIntervalsService, LocalDateTime, ExecutionContext) => Future[EvaluateIntervalsResponse]

  /**
    * A specific [[StateAction]] to trigger the evaluation of a specific radio
    * source. The provided [[EvalFunc]] has to be invoked, and the state
    * service has to be triggered again when the response becomes available.
    *
    * @param evalFunc the evaluation function to be invoked
    */
  case class TriggerEvaluation(evalFunc: EvalFunc) extends StateAction

  /**
    * Type alias for a function that invokes the
    * [[ReplacementSourceSelectionService]] to obtain a replacement source if
    * the current source reaches a forbidden interval. The function expects the
    * required services and returns a ''Future'' with the result produced by
    * the replacement service.
    */
  type ReplaceFunc = (ReplacementSourceSelectionService, EvaluateIntervalsService, ActorSystem) =>
    Future[ReplacementSourceSelectionResult]

  /**
    * A specific [[StateAction]] to trigger the selection of a replacement
    * source. Analogously to [[TriggerEvaluation]], the provided function has
    * to be invoked, and the result has to be passed to the state service when
    * it becomes available.
    *
    * @param replaceFunc the replacement function to be invoked
    */
  case class TriggerReplacementSelection(replaceFunc: ReplaceFunc) extends StateAction

  /**
    * A specific [[StateAction]] to trigger a (re-)evaluation of the current
    * radio source after a delay. This action is typically generated when there
    * is a change in the currently played source (which may be a replacement).
    * Based on the last evaluation, it can be decided how long this source can
    * be played. Then another evaluation has to be done.
    *
    * @param delay the delay until the next evaluation is due
    * @param seqNo the sequence number
    */
  case class ScheduleSourceEvaluation(delay: FiniteDuration, seqNo: Int) extends StateAction

  /**
    * A specific [[StateAction]] that indicates that playback should switch
    * to a new current source. This action is triggered when the user selects
    * a new source or when playback switches back from a replacement source to
    * the actually selected source. In the latter case, the replacement source
    * is provided.
    *
    * @param currentSource        the selected current source
    * @param optReplacementSource the optional replacement source
    */
  case class PlayCurrentSource(currentSource: RadioSource,
                               optReplacementSource: Option[RadioSource]) extends StateAction

  /**
    * A specific [[StateAction]] that indicates that playback should switch
    * from the selected source to the given replacement source.
    *
    * @param currentSource     the selected current source
    * @param replacementSource the replacement source
    */
  case class StartReplacementSource(currentSource: RadioSource,
                                    replacementSource: RadioSource) extends StateAction

  /**
    * A data class representing the current state of radio sources.
    *
    * @param sourcesConfig     the configuration for radio sources
    * @param rankedSources     a map with sources ordered by their ranking
    * @param currentSource     an ''Option'' with the currently played source
    * @param replacementSource an ''Option'' with a replacement source that is
    *                          currently active
    * @param disabledSources   a set with sources that are currently disabled
    * @param actions           the actions to trigger after an update
    * @param seqNo             the current sequence number of the state
    */
  case class RadioSourceState(sourcesConfig: RadioSourceConfig,
                              rankedSources: SortedMap[Int, Seq[RadioSource]],
                              currentSource: Option[RadioSource],
                              replacementSource: Option[RadioSource],
                              disabledSources: Set[RadioSource],
                              actions: List[StateAction],
                              seqNo: Int)

  /**
    * The type for updates of the radio source state that yields a specific
    * result.
    */
  type StateUpdate[A] = State[RadioSourceState, A]
}

/**
  * A trait defining a service that manages the radio source that is currently
  * played.
  *
  * The radio player engine is responsible for selecting the radio source to be
  * played based on the user's choice and configuration options. The following
  * points have to be taken into account:
  *
  *  - The user defines the main radio source that should be played.
  *  - The exclusion intervals defined for radio sources are evaluated. When a
  *    time slot is reached in which the main source must not be played, a
  *    replacement source is selected.
  *  - Specific sources (including the main source) can be marked as
  *    (temporarily) excluded. That way other exclusion criteria (not based on
  *    strict time intervals) can be modelled. For instance, certain patterns
  *    in the metadata of a radio source could lead to its exclusion.
  *
  * The purpose of this service is to update a state object storing all
  * relevant information when specific triggers arrive. An update of the state
  * can also require the execution of actions, such as switching to another
  * radio source or scheduling another check for exclusions in the future.
  */
trait RadioSourceStateService {
  /**
    * Updates the state for the given configuration of sources.
    *
    * @param sourcesConfig the configuration for radio sources
    * @return the updated state
    */
  def initSourcesConfig(sourcesConfig: RadioSourceConfig): StateUpdate[Unit]

  /**
    * Updates the state for an evaluation of the current source. This function
    * is called when a scheduled reevaluation of the currently played source is
    * due. The processing of this notification typically results in an action
    * to trigger the evaluation service.
    *
    * @param seqNo the sequence number passed to the scheduler
    * @return the updated state
    */
  def evaluateCurrentSource(seqNo: Int): StateUpdate[Unit]

  /**
    * Updates the state when the response of a request to evaluate the current
    * source arrives. It has to be decided then whether a switch to another
    * source is necessary.
    *
    * @param response the response from the evaluation service
    * @param refTime  the current time
    * @return the updated state
    */
  def evaluationResultArrived(response: EvaluateIntervalsResponse, refTime: LocalDateTime): StateUpdate[Unit]

  /**
    * Updates the state when a result with a replacement source arrives. If a
    * replacement source could be found, playback switches to this source.
    *
    * @param result  the result with the replacement source
    * @param refTime the current time
    * @return the updated state
    */
  def replacementResultArrived(result: ReplacementSourceSelectionResult, refTime: LocalDateTime): StateUpdate[Unit]

  /**
    * Updates the state for a new radio source to be played. Playback starts
    * with this source, and an evaluation is triggered immediately.
    *
    * @param source the new current source
    * @return the updated state
    */
  def setCurrentSource(source: RadioSource): StateUpdate[Unit]

  /**
    * Updates the state for a radio source that must not be played currently.
    * As long as this source remains in this state, it is ignored when
    * searching for a replacement source. If it is the current source, a
    * replacement source selection is triggered immediately.
    *
    * @param source the source to be disabled
    * @return the updated state
    */
  def disableSource(source: RadioSource): StateUpdate[Unit]

  /**
    * Updates the state for a radio source that can now be played again. This
    * may cause a re-evaluation of the source to be played.
    *
    * @param source the source to be enabled
    * @return the updated state
    */
  def enableSource(source: RadioSource): StateUpdate[Unit]

  /**
    * Updates the state by resetting the current list of actions and returns
    * them. A caller should then process the actions.
    *
    * @return the updated state and the list of actions
    */
  def readActions(): StateUpdate[List[StateAction]]
}

object RadioSourceStateServiceImpl {
  /** Constant for the initial radio source state. */
  final val InitialState: RadioSourceState = RadioSourceState(sourcesConfig = RadioSourceConfig.Empty,
    rankedSources = SortedMap.empty,
    currentSource = None,
    replacementSource = None,
    disabledSources = Set.empty,
    actions = List.empty,
    seqNo = 0)

  /**
    * The ordering for the sorted map of ranked sources. Sources with a high
    * ranking should be listed first; therefore, the natural order of [[Int]]
    * has to be reverted.
    */
  private val RankingOrdering = implicitly[Ordering[Int]].reverse

  /**
    * Returns an updated list of actions that contains a new action to evaluate
    * the current source if it exists. Otherwise, the list of actions from the
    * given state is returned without changes.
    *
    * @param state         the current [[RadioSourceState]]
    * @param sourcesConfig the configuration of radio sources
    * @param radioConfig   the configuration of the radio player
    * @return the updated list with actions
    */
  private def addTriggerEvaluationAction(state: RadioSourceState,
                                         sourcesConfig: RadioSourceConfig,
                                         radioConfig: RadioPlayerConfig): List[RadioSourceStateService.StateAction] =
    addActionForSource(state) { source =>
      val evalFunc: EvalFunc = (service, time, ec) =>
        if (state.disabledSources.contains(source)) {
          val until = new LazyDate(time.plusSeconds(radioConfig.maximumEvalDelay.toSeconds))
          Future.successful(EvaluateIntervalsResponse(Inside(until), state.seqNo))
        } else service.evaluateIntervals(sourcesConfig.exclusions(source), time, state.seqNo)(ec)
      TriggerEvaluation(evalFunc)
    }

  /**
    * Returns an updated state in case the current radio source can be played.
    * In this case, a future check has to be scheduled. If there is an active
    * replacement source, it has to be canceled now.
    *
    * @param config         the current configuration of the radio player
    * @param state          the current state
    * @param nextCheckDelay the delay until the next source evaluation
    * @return the updated state
    */
  private def updateCurrentSourceActive(config: RadioPlayerConfig,
                                        state: RadioSourceState,
                                        nextCheckDelay: FiniteDuration): RadioSourceState = {
    val (actions, nextSeq) = state.replacementSource match {
      case optReplacement@Some(_) =>
        (addActionForSource(state) {
          PlayCurrentSource(_, optReplacement)
        }, state.seqNo + 1)
      case None =>
        (state.actions, state.seqNo)
    }
    state.copy(actions = createScheduledEvaluation(config, nextCheckDelay, nextSeq) :: actions,
      replacementSource = None, seqNo = nextSeq)
  }

  /**
    * Helper function to add an action to the actions contained in a state that
    * depends on the current radio source. The action is created and added only
    * if a current source is available.
    *
    * @param state  the state
    * @param action a function to generate the action
    * @return the updated list of actions
    */
  private def addActionForSource(state: RadioSourceState)(action: RadioSource => RadioSourceStateService.StateAction):
  List[RadioSourceStateService.StateAction] =
    state.currentSource map { source =>
      action(source) :: state.actions
    } getOrElse state.actions

  /**
    * Creates a [[ScheduleSourceEvaluation]] object from the given parameters
    * applying the configured maximum delay.
    *
    * @param config the current configuration of the radio player
    * @param delay  the delay
    * @param seqNo  the sequence number
    * @return the [[ScheduleSourceEvaluation]] instance
    */
  private def createScheduledEvaluation(config: RadioPlayerConfig, delay: FiniteDuration, seqNo: Int):
  ScheduleSourceEvaluation =
    ScheduleSourceEvaluation(delay.min(config.maximumEvalDelay), seqNo)
}

/**
  * The default implementation of [[RadioSourceStateService]].
  *
  * @param config the [[RadioPlayerConfig]]
  */
class RadioSourceStateServiceImpl(val config: RadioPlayerConfig) extends RadioSourceStateService {

  import RadioSourceStateServiceImpl._

  override def initSourcesConfig(sourcesConfig: RadioSourceConfig): StateUpdate[Unit] = modify { s =>
    val rankedSources = sourcesConfig.sources.groupBy(sourcesConfig.ranking)
    val nextActions = addTriggerEvaluationAction(s, sourcesConfig, config)

    s.copy(sourcesConfig = sourcesConfig,
      rankedSources = TreeMap(rankedSources.toIndexedSeq: _*)(RankingOrdering),
      actions = nextActions,
      seqNo = s.seqNo + 1)
  }

  override def evaluateCurrentSource(seqNo: Int): StateUpdate[Unit] = modify { s =>
    if (seqNo == s.seqNo) {
      s.copy(actions = addTriggerEvaluationAction(s, s.sourcesConfig, config))
    } else s
  }

  override def evaluationResultArrived(response: EvaluateIntervalsResponse, refTime: LocalDateTime):
  StateUpdate[Unit] = modify { s =>
    if (response.seqNo == s.seqNo) {
      response.result match {
        case Before(start) =>
          val delay = durationBetween(refTime, start.value)
          updateCurrentSourceActive(config, s, delay)

        case Inside(until) =>
          val nextActions = addActionForSource(s) { source =>
            val replaceFunc: ReplaceFunc = (replaceService, evalService, system) =>
              replaceService.selectReplacementSource(s.sourcesConfig, s.rankedSources, s.disabledSources + source,
                until.value, s.seqNo, evalService)(system)
            TriggerReplacementSelection(replaceFunc)
          }
          s.copy(actions = nextActions)

        case _ =>
          updateCurrentSourceActive(config, s, config.maximumEvalDelay)
      }
    } else s
  }

  override def replacementResultArrived(result: ReplacementSourceSelectionResult, refTime: LocalDateTime):
  StateUpdate[Unit] = modify { s =>
    if (result.seqNo == s.seqNo) {
      val nextSeq = s.seqNo + 1
      result.selectedSource match {
        case Some(replacement) =>
          val delay = durationBetween(refTime, replacement.untilDate)
          val actions = if (s.replacementSource contains replacement.source) s.actions
          else addActionForSource(s) {
            StartReplacementSource(_, replacement.source)
          }
          s.copy(replacementSource = Some(replacement.source), seqNo = nextSeq,
            actions = createScheduledEvaluation(config, delay, nextSeq) :: actions)

        case None =>
          s.copy(actions = ScheduleSourceEvaluation(config.retryFailedReplacement, nextSeq) :: s.actions,
            replacementSource = None, seqNo = nextSeq)
      }
    } else s
  }

  override def setCurrentSource(source: RadioSource): StateUpdate[Unit] = modify { s =>
    val nextSeq = s.seqNo + 1
    val evalFunc: EvalFunc = (service, time, ec) =>
      service.evaluateIntervals(s.sourcesConfig.exclusions(source), time, nextSeq)(ec)
    val actionsWithTrigger = TriggerEvaluation(evalFunc) :: s.actions
    val nextActions = if (s.currentSource.contains(source) && s.replacementSource.isEmpty) actionsWithTrigger
    else PlayCurrentSource(source, s.replacementSource) :: actionsWithTrigger
    s.copy(currentSource = Some(source), replacementSource = None, seqNo = nextSeq, actions = nextActions)
  }

  override def disableSource(source: RadioSource): StateUpdate[Unit] = modify { s =>
    val stateWithDisabledSource = s.copy(disabledSources = s.disabledSources + source, seqNo = s.seqNo + 1)
    if (s.currentSource.contains(source) || s.replacementSource.contains(source)) {
      val nextActions = addTriggerEvaluationAction(stateWithDisabledSource, s.sourcesConfig, config)
      stateWithDisabledSource.copy(actions = nextActions)
    } else stateWithDisabledSource
  }

  override def enableSource(source: RadioSource): StateUpdate[Unit] = modify { s =>
    val stateWithEnabledSource = s.copy(disabledSources = s.disabledSources - source, seqNo = s.seqNo + 1)
    if (s.currentSource.contains(source) || s.replacementSource.exists { replacement =>
      s.sourcesConfig.ranking(source) > s.sourcesConfig.ranking(replacement)
    }) {
      val nextActions = addTriggerEvaluationAction(stateWithEnabledSource, s.sourcesConfig, config)
      stateWithEnabledSource.copy(actions = nextActions)
    } else stateWithEnabledSource
  }

  override def readActions(): StateUpdate[List[StateAction]] = State { s =>
    (s.copy(actions = List.empty), s.actions.reverse)
  }

  /**
    * Calculates the duration between the given times (with second
    * granularity).
    *
    * @param startTime the start time
    * @param endTime   the end time
    * @return the duration between these times
    */
  private def durationBetween(startTime: LocalDateTime, endTime: LocalDateTime): FiniteDuration =
    Try {
      // This can fail if the time delta is too big; the Duration class supports only about 292 years.
      java.time.Duration.between(startTime, endTime).toSeconds.seconds
    } getOrElse config.maximumEvalDelay
}
