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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor.ScheduledInvocationCommand
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource, RadioSourceConfig}

/**
  * Implementation of an actor that controls the radio source to be played.
  *
  * This actor has the responsibility to play a selected radio source as far as
  * possible. It adheres to the exclusion intervals defined for this source or
  * other exclusion criteria and switches to a replacement source if the
  * current source cannot be played at the moment. So, a client of the radio
  * player engine basically specifies the source to be played and a set of
  * constraints (exclusions); it is then the job of this actor to fulfill these
  * constraints as far as possible.
  *
  * To implement the partially complex scheduling and checking logic for the
  * current source, this actor delegates to a number of helper actors and
  * services. It creates these actors as child actors, so that their lifecycle
  * is tight to this main controller actor.
  */
object RadioControlActor {
  /** The name of the child actor managing the radio source state. */
  final val SourceStateActorName = "radioSourceStateActor"

  /**
    * The base trait for the commands supported by this actor implementation.
    */
  sealed trait RadioControlCommand

  /**
    * A command to initialize or update the configuration for the available
    * radio sources.
    *
    * @param config the current configuration of radio sources
    */
  case class InitRadioSourceConfig(config: RadioSourceConfig) extends RadioControlCommand

  /**
    * A command that tells this actor to make a new [[RadioSource]] the current
    * one. If possible, playback switches immediately to this source.
    * Otherwise, a replacement source needs to be found.
    *
    * @param source the new current radio source to be played
    */
  case class SelectRadioSource(source: RadioSource) extends RadioControlCommand

  /**
    * A trait that defines a factory function for creating a ''Behavior'' for a
    * new actor instance.
    */
  trait Factory {
    /**
      * Returns a ''Behavior'' to create a new instance of this actor
      * implementation. The function expects a number of actors and services
      * this actor depends on. In addition, it is possible (mainly for testing
      * purposes) to provide factories for creating child actors.
      *
      * @param eventActor         the actor for publishing events
      * @param scheduleActor      the actor for scheduled invocations
      * @param evalService        the service to evaluate interval queries
      * @param replacementService the service to select a replacement source
      * @param stateService       the service to manage the source state
      * @param stateActorFactory  factory to create the state management actor
      * @return the behavior for a new actor instance
      */
    def apply(eventActor: ActorRef[RadioEvent],
              scheduleActor: ActorRef[ScheduledInvocationCommand],
              evalService: EvaluateIntervalsService = EvaluateIntervalsServiceImpl,
              replacementService: ReplacementSourceSelectionService = ReplacementSourceSelectionServiceImpl,
              stateService: RadioSourceStateService = RadioSourceStateServiceImpl,
              stateActorFactory: RadioSourceStateActor.Factory = RadioSourceStateActor.behavior):
    Behavior[RadioControlCommand]
  }

  /**
    * A default [[Factory]] instance that can be used to create new actor
    * instances.
    */
  final val behavior: Factory = (eventActor: ActorRef[RadioEvent],
                                 scheduleActor: ActorRef[ScheduledInvocationCommand],
                                 evalService: EvaluateIntervalsService,
                                 replacementService: ReplacementSourceSelectionService,
                                 stateService: RadioSourceStateService,
                                 stateActorFactory: RadioSourceStateActor.Factory) =>
    Behaviors.setup { context =>
      val stateActor = context.spawn(stateActorFactory(stateService, evalService, replacementService, scheduleActor,
        null, eventActor), SourceStateActorName)
      handle(stateActor)
    }

  /**
    * The main message handling function of this actor.
    *
    * @param stateActor the state management actor
    * @return the updated behavior
    */
  private def handle(stateActor: ActorRef[RadioSourceStateActor.RadioSourceStateCommand]):
  Behavior[RadioControlCommand] = Behaviors.receiveMessage {
    case InitRadioSourceConfig(config) =>
      stateActor ! RadioSourceStateActor.InitRadioSourceConfig(config)
      Behaviors.same

    case SelectRadioSource(source) =>
      stateActor ! RadioSourceStateActor.RadioSourceSelected(source)
      Behaviors.same
  }
}
