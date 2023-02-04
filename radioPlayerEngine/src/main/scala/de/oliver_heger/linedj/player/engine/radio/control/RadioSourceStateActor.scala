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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor.ScheduledInvocationCommand
import de.oliver_heger.linedj.player.engine.radio._

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext

/**
  * An actor implementation that manages an [[RadioSourceStateService]]
  * instance.
  *
  * This actor reacts on a number of messages relevant for the state of radio
  * sources. It converts such messages to method calls to the state service and
  * interprets the responses, especially the
  * [[RadioSourceStateService.StateAction]] responses. These may lead to
  * further messages being sent to other helper actors.
  */
private object RadioSourceStateActor {
  /**
    * The base trait of the command hierarchy supported by this actor.
    */
  sealed trait RadioSourceStateCommand

  /**
    * A command that initializes or updates the configuration of the radio
    * sources available.
    *
    * @param config the new configuration of radio sources
    */
  case class InitRadioSourceConfig(config: RadioSourceConfig) extends RadioSourceStateCommand

  /**
    * A command that indicates that the user has selected another radio source
    * to be played.
    *
    * @param radioSource the new current radio source
    */
  case class RadioSourceSelected(radioSource: RadioSource) extends RadioSourceStateCommand

  /**
    * An internal command this actor pipes to itself when the result of a
    * source evaluation becomes available.
    *
    * @param result the evaluation response
    */
  private case class EvaluationResultArrived(result: EvaluateIntervalsService.EvaluateIntervalsResponse)
    extends RadioSourceStateCommand

  /**
    * An internal command this actor pipes to itself when the result of a
    * replacement source selection request becomes available.
    *
    * @param result the replacement result
    */
  private case class ReplacementResultArrived(result:
                                              ReplacementSourceSelectionService.ReplacementSourceSelectionResult)
    extends RadioSourceStateCommand

  /**
    * An internal command this actor receives from the scheduled invocation
    * actor when it is time to re-evaluate the current source.
    *
    * @param seqNo the sequence number
    */
  private case class EvalCurrentSource(seqNo: Int) extends RadioSourceStateCommand

  /**
    * An internal data class collecting the complex dependencies of this actor.
    *
    * @param context        the actor context
    * @param stateService   the service managing the radio source state
    * @param evalService    the service to evaluate radio sources
    * @param replaceService the service to select a replacement source
    * @param scheduleActor  the actor for scheduled invocations
    * @param playbackActor  the actor for playing radio sources
    * @param eventActor     the actor for publishing events
    */
  private case class Dependencies(context: ActorContext[RadioSourceStateCommand],
                                  stateService: RadioSourceStateService,
                                  evalService: EvaluateIntervalsService,
                                  replaceService: ReplacementSourceSelectionService,
                                  scheduleActor: ActorRef[ScheduledInvocationCommand],
                                  playbackActor: ActorRef[RadioControlActor.SwitchToSource],
                                  eventActor: ActorRef[RadioEvent])

  /**
    * Returns the behavior to create an instance of this actor implementation.
    * The function expects a number of parameters for the dependencies used by
    * this actor.
    *
    * @param stateService   the service managing the radio source state
    * @param evalService    the service to evaluate radio sources
    * @param replaceService the service to select a replacement source
    * @param scheduleActor  the actor for scheduled invocations
    * @param playbackActor  the actor for playing radio sources
    * @param eventActor     the actor for publishing events
    * @return the behavior for a new actor instance
    */
  def apply(stateService: RadioSourceStateService,
            evalService: EvaluateIntervalsService,
            replaceService: ReplacementSourceSelectionService,
            scheduleActor: ActorRef[ScheduledInvocationCommand],
            playbackActor: ActorRef[RadioControlActor.SwitchToSource],
            eventActor: ActorRef[RadioEvent]):
  Behavior[RadioSourceStateCommand] = Behaviors.setup[RadioSourceStateCommand] { context =>
    val dependencies = Dependencies(context, stateService, evalService, replaceService,
      scheduleActor, playbackActor, eventActor)
    handle(dependencies, RadioSourceStateServiceImpl.InitialState)
  }

  /**
    * The main message handling function of this actor.
    *
    * @param dependencies an object collecting all dependencies
    * @param state        the current radio source state
    * @return the behavior of this actor
    */
  private def handle(dependencies: Dependencies, state: RadioSourceStateService.RadioSourceState):
  Behavior[RadioSourceStateCommand] = {
    def applyUpdate(update: RadioSourceStateService.StateUpdate[Unit]): Behavior[RadioSourceStateCommand] =
      updateState(dependencies, state, update)

    Behaviors.receiveMessage {
      case InitRadioSourceConfig(config) =>
        applyUpdate(dependencies.stateService.initSourcesConfig(config))

      case RadioSourceSelected(radioSource) =>
        applyUpdate(dependencies.stateService.setCurrentSource(radioSource))

      case EvaluationResultArrived(result) =>
        applyUpdate(dependencies.stateService.evaluationResultArrived(result, LocalDateTime.now()))

      case ReplacementResultArrived(result) =>
        applyUpdate(dependencies.stateService.replacementResultArrived(result, LocalDateTime.now()))

      case EvalCurrentSource(seqNo) =>
        applyUpdate(dependencies.stateService.evaluateCurrentSource(seqNo))
    }
  }

  /**
    * Updates the current radio source state according to the given update.
    * Afterwards, state actions are read and processed.
    *
    * @param dependencies an object collecting all dependencies
    * @param state        the current radio source state
    * @param update       the [[RadioSourceStateService.StateUpdate]] object
    * @return the updated behavior of this actor
    */
  private def updateState(dependencies: Dependencies,
                          state: RadioSourceStateService.RadioSourceState,
                          update: RadioSourceStateService.StateUpdate[Unit]): Behavior[RadioSourceStateCommand] = {
    val fullUpdate = for {
      _ <- update
      stateActions <- dependencies.stateService.readActions()
    } yield stateActions

    val (nextState, actions) = fullUpdate(state)
    actions foreach (processStateAction(dependencies, _))

    handle(dependencies, nextState)
  }

  /**
    * Processes the given [[RadioSourceStateService.StateAction]].
    *
    * @param dependencies the object with all dependencies
    * @param action       the action to be processed
    */
  private def processStateAction(dependencies: Dependencies, action: RadioSourceStateService.StateAction): Unit = {
    implicit val ec: ExecutionContext = dependencies.context.system.executionContext

    action match {
      case RadioSourceStateService.PlayCurrentSource(currentSource, optReplacementSource) =>
        optReplacementSource foreach { replacement =>
          dependencies.eventActor ! RadioSourceReplacementEndEvent(replacement)
        }
        dependencies.playbackActor ! RadioControlActor.SwitchToSource(currentSource)

      case RadioSourceStateService.StartReplacementSource(currentSource, replacementSource) =>
        dependencies.eventActor ! RadioSourceReplacementStartEvent(currentSource, replacementSource)
        dependencies.playbackActor ! RadioControlActor.SwitchToSource(replacementSource)

      case RadioSourceStateService.TriggerEvaluation(evalFunc) =>
        evalFunc(dependencies.evalService, LocalDateTime.now(), ec) foreach { response =>
          dependencies.context.self ! EvaluationResultArrived(response)
        }

      case RadioSourceStateService.TriggerReplacementSelection(replaceFunc) =>
        val system = dependencies.context.system.classicSystem
        replaceFunc(dependencies.replaceService, dependencies.evalService, system) foreach { result =>
          dependencies.context.self ! ReplacementResultArrived(result)
        }

      case RadioSourceStateService.ScheduleSourceEvaluation(delay, seqNo) =>
        val evalCommand = EvalCurrentSource(seqNo)
        val invocation = ScheduledInvocationActor.typedInvocationCommand(delay,
          dependencies.context.self, evalCommand)
        dependencies.scheduleActor ! invocation
    }
  }
}
