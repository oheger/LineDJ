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
import akka.util.Timeout
import akka.{actor => classic}
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor.ScheduledInvocationCommand
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, PlaybackContextFactoryActor}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamManagerActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

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

  /** The name of the child actor managing the playback state. */
  final val PlayStateActorName = "radioPlaybackStateActor"

  /** The name of the child actor managing the error state. */
  final val ErrorStateActorName = "radioErrorStateActor"

  /** The name of the child actor managing the metadata state. */
  final val MetadataStateActorName = "radioMetadataExclusionActor"

  /** The name of the child actor that checks for stalled playback. */
  final val GuardianActorName = "radioPlaybackGuardianActor"

  /** The default timeout when using the Ask pattern. */
  private val DefaultAskTimeout = Timeout(5.seconds)

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
    * A command to initialize or update the global configuration for metadata
    * exclusions.
    *
    * @param config the new metadata configuration
    */
  case class InitMetadataConfig(config: MetadataConfig) extends RadioControlCommand

  /**
    * A command that tells this actor to make a new [[RadioSource]] the current
    * one. If possible, playback switches immediately to this source.
    * Otherwise, a replacement source needs to be found.
    *
    * @param source the new current radio source to be played
    */
  case class SelectRadioSource(source: RadioSource) extends RadioControlCommand

  /**
    * A command to start playback. If a radio source is already selected,
    * playback of this source starts now. (If playback is already active, this
    * command has no effect.)
    */
  case object StartPlayback extends RadioControlCommand

  /**
    * A command to stop playback. If a radio source is currently played,
    * playback is stopped. Otherwise, this command has no effect.
    */
  case object StopPlayback extends RadioControlCommand

  /**
    * A command to request the current set of radio sources whose playback
    * failed for some reason.
    *
    * @param receiver the actor to send the response to
    */
  case class GetSourcesInErrorState(receiver: ActorRef[ErrorStateActor.SourcesInErrorState])
    extends RadioControlCommand

  /**
    * A command telling this actor to stop itself.
    */
  case object Stop extends RadioControlCommand

  /**
    * A command for querying the current playback state.
    *
    * @param replyTo reference to the actor to send the response to
    */
  case class GetPlaybackState(replyTo: ActorRef[CurrentPlaybackState]) extends RadioControlCommand

  /**
    * A data class containing information about the current playback state. An
    * instance is sent as response of a [[GetPlaybackState]] request. Note that
    * there is the possible combination that playback is enabled, but no source
    * is available. Since this typically does not make sense in practice, for
    * this combination the playback active flag is set to '''false'''.
    *
    * @param currentSource  an option with the current radio source
    * @param playbackActive flag whether playback is currently active
    */
  case class CurrentPlaybackState(currentSource: Option[RadioSource],
                                  playbackActive: Boolean)

  /**
    * An internal command indicating that playback should change to the
    * provided radio source. This can be the current source or a replacement
    * source.
    *
    * @param source the source to be played
    */
  private case class SwitchToSource(source: RadioSource) extends RadioControlCommand

  /**
    * An internal command indicating that the given radio source has been
    * disabled.
    *
    * @param source the affected source
    */
  private case class SourceDisabled(source: RadioSource) extends RadioControlCommand

  /**
    * An internal command indicating that the given radio source has been
    * enabled.
    *
    * @param source the affected source
    */
  private case class SourceEnabled(source: RadioSource) extends RadioControlCommand

  /**
    * An internal command this actor sends to itself when the playback state
    * from the state actor was received.
    *
    * @param state     the playback state
    * @param forwardTo the client to forward this state
    */
  private case class ForwardPlaybackState(state: CurrentPlaybackState,
                                          forwardTo: ActorRef[CurrentPlaybackState]) extends RadioControlCommand

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
      * @param config                the configuration of the player
      * @param eventActor            the actor for publishing events
      * @param eventManagerActor     the actor managing event listeners
      * @param facadeActor           the player facade actor
      * @param scheduleActor         the actor for scheduled invocations
      * @param factoryActor          the actor managing playback context
      *                              factories
      * @param streamManagerActor    the actor managing radio stream actors
      * @param optEvalService        the optional service to evaluate interval
      *                              queries (None for default)
      * @param optReplacementService the optional service to select a
      *                              replacement source (None for default)
      * @param optStateService       the optional service to manage the source
      *                              state (None for default)
      * @param askTimeout            the timeout for Ask interactions
      * @param stateActorFactory     factory to create the state management actor
      * @param playActorFactory      factory to create the playback state actor
      * @param errorActorFactory     factory to create the error state actor
      * @param metaActorFactory      factory to create the metadata state actor
      * @param guardianActorFactory  factory to create the playback guardian
      *                              actor
      * @return the behavior for a new actor instance
      */
    def apply(config: RadioPlayerConfig,
              eventActor: ActorRef[RadioEvent],
              eventManagerActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
              facadeActor: classic.ActorRef,
              scheduleActor: ActorRef[ScheduledInvocationCommand],
              factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
              streamManagerActor: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
              optEvalService: Option[EvaluateIntervalsService] = None,
              optReplacementService: Option[ReplacementSourceSelectionService] = None,
              optStateService: Option[RadioSourceStateService] = None,
              askTimeout: Timeout = DefaultAskTimeout,
              stateActorFactory: RadioSourceStateActor.Factory = RadioSourceStateActor.behavior,
              playActorFactory: PlaybackStateActor.Factory = PlaybackStateActor.behavior,
              errorActorFactory: ErrorStateActor.Factory = ErrorStateActor.errorStateBehavior,
              metaActorFactory: MetadataStateActor.Factory = MetadataStateActor.metadataStateBehavior,
              guardianActorFactory: PlaybackGuardianActor.Factory = PlaybackGuardianActor.behavior):
    Behavior[RadioControlCommand]
  }

  /**
    * A default [[Factory]] instance that can be used to create new actor
    * instances.
    */
  final val behavior: Factory = (config: RadioPlayerConfig,
                                 eventActor: ActorRef[RadioEvent],
                                 eventManagerActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
                                 facadeActor: classic.ActorRef,
                                 scheduleActor: ActorRef[ScheduledInvocationCommand],
                                 factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                                 streamManagerActor: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
                                 optEvalService: Option[EvaluateIntervalsService],
                                 optReplacementService: Option[ReplacementSourceSelectionService],
                                 optStateService: Option[RadioSourceStateService],
                                 askTimeout: Timeout,
                                 stateActorFactory: RadioSourceStateActor.Factory,
                                 playActorFactory: PlaybackStateActor.Factory,
                                 errorActorFactory: ErrorStateActor.Factory,
                                 metaActorFactory: MetadataStateActor.Factory,
                                 guardianActorFactory: PlaybackGuardianActor.Factory) =>
    Behaviors.setup { context =>
      val switchSourceAdapter = context.messageAdapter[RadioControlProtocol.SwitchToSource] { msg =>
        SwitchToSource(msg.source)
      }

      val enabledStateAdapter = context.messageAdapter[RadioControlProtocol.SourceEnabledStateCommand] {
        case RadioControlProtocol.DisableSource(source) =>
          SourceDisabled(source)
        case RadioControlProtocol.EnableSource(source) =>
          SourceEnabled(source)
      }

      val evalService = optEvalService getOrElse EvaluateIntervalsServiceImpl
      val replacementService = optReplacementService getOrElse ReplacementSourceSelectionServiceImpl
      val stateService = optStateService getOrElse new RadioSourceStateServiceImpl(config)
      val sourceStateActor = context.spawn(stateActorFactory(stateService, evalService, replacementService,
        scheduleActor, switchSourceAdapter, eventActor), SourceStateActorName)
      val playStateActor = context.spawn(playActorFactory(facadeActor, eventManagerActor), PlayStateActorName)
      val errorStateActor = context.spawn(errorActorFactory(config,
        enabledStateAdapter,
        factoryActor,
        scheduleActor,
        eventManagerActor,
        streamManagerActor), ErrorStateActorName)
      val metadataStateActor = context.spawn(metaActorFactory(config,
        enabledStateAdapter,
        scheduleActor,
        eventManagerActor,
        streamManagerActor,
        evalService,
        new MetadataExclusionFinderServiceImpl(evalService)), MetadataStateActorName)
      context.spawn(guardianActorFactory(config.stalledPlaybackCheck,
        enabledStateAdapter,
        scheduleActor,
        eventManagerActor), GuardianActorName)

      handle(context, sourceStateActor, playStateActor, errorStateActor, metadataStateActor, askTimeout)
    }

  /**
    * The main message handling function of this actor.
    *
    * @param context            the actor context
    * @param sourceStateActor   the source state management actor
    * @param playStateActor     the playback state management actor
    * @param errorStateActor    the actor managing the error state
    * @param metadataStateActor the actor managing the metadata state
    * @param askTimeout         the timeout for ask interactions
    * @return the updated behavior
    */
  private def handle(context: ActorContext[RadioControlCommand],
                     sourceStateActor: ActorRef[RadioSourceStateActor.RadioSourceStateCommand],
                     playStateActor: ActorRef[PlaybackStateActor.PlaybackStateCommand],
                     errorStateActor: ActorRef[ErrorStateActor.ErrorStateCommand],
                     metadataStateActor: ActorRef[MetadataStateActor.MetadataExclusionStateCommand],
                     askTimeout: Timeout): Behavior[RadioControlCommand] = Behaviors.receiveMessage {
    case InitRadioSourceConfig(config) =>
      sourceStateActor ! RadioSourceStateActor.InitRadioSourceConfig(config)
      Behaviors.same

    case InitMetadataConfig(config) =>
      metadataStateActor ! MetadataStateActor.InitMetadataConfig(config)
      Behaviors.same

    case SelectRadioSource(source) =>
      sourceStateActor ! RadioSourceStateActor.RadioSourceSelected(source)
      Behaviors.same

    case SourceDisabled(source) =>
      sourceStateActor ! RadioSourceStateActor.RadioSourceDisabled(source)
      Behaviors.same

    case SourceEnabled(source) =>
      sourceStateActor ! RadioSourceStateActor.RadioSourceEnabled(source)
      Behaviors.same

    case StartPlayback =>
      playStateActor ! PlaybackStateActor.StartPlayback
      Behaviors.same

    case StopPlayback =>
      playStateActor ! PlaybackStateActor.StopPlayback
      Behaviors.same

    case SwitchToSource(source) =>
      playStateActor ! PlaybackStateActor.PlaybackSource(source)
      Behaviors.same

    case GetSourcesInErrorState(receiver) =>
      errorStateActor ! ErrorStateActor.GetSourcesInErrorState(receiver)
      Behaviors.same

    case GetPlaybackState(replyTo) =>
      implicit val timeout: Timeout = askTimeout
      context.ask(playStateActor, PlaybackStateActor.GetPlaybackState.apply) {
        case Failure(exception) =>
          context.log.error("Error when querying playback state.", exception)
          ForwardPlaybackState(CurrentPlaybackState(None, playbackActive = false), replyTo)
        case Success(value) =>
          val state = CurrentPlaybackState(value.currentSource, value.playbackActive)
          ForwardPlaybackState(state, replyTo)
      }
      Behaviors.same

    case ForwardPlaybackState(state, forwardTo) =>
      forwardTo ! state
      Behaviors.same

    case Stop =>
      Behaviors.stopped
  }
}
