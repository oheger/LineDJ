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

import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.radio._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration.FiniteDuration

/**
  * A module providing an actor implementation that can detect and remedy a
  * stalled radio playback.
  *
  * It has been observed in practice that a radio stream suddenly stalls
  * without any error being detected. This happens mostly after switching to
  * another radio source, but also in the midst of playback (maybe caused by a
  * network hick-up?). This actor implementation should guard against such
  * problems.
  *
  * The idea behind this actor is that it registers itself as listener for
  * radio events. As long as playback is not stopped it checks periodically
  * whether it has retrieved a playback progress event. If this is the case,
  * everything is considered to be normal. Otherwise, the radio stream seems to
  * be hanging. It then disables the current radio source. This should trigger
  * the playback of another source including a reset of the player engine. On
  * receiving a playback progress event again, it enables all the sources it
  * has currently disabled again, so that playback can be retried.
  */
object PlaybackGuardianActor:
  /**
    * The base command trait for this actor.
    */
  sealed trait PlaybackGuardianCommand

  /**
    * An internal command the guardian actor sends to itself to process a
    * received radio event.
    *
    * @param event the event to process
    */
  private case class RadioEventReceived(event: RadioEvent) extends PlaybackGuardianCommand

  /**
    * An internal command that triggers a check whether the current source has
    * generated a playback progress event. The command is scheduled in regular
    * intervals.
    */
  private case object CheckPlaybackProgress extends PlaybackGuardianCommand

  /**
    * A data class representing the current state of the guardian actor.
    *
    * @param currentSource   an ''Option'' with the source that is currently
    *                        played
    * @param disabledSources the sources that have been disabled
    * @param gotProgress     flag whether a playback progress event was
    *                        received
    */
  private case class GuardianState(currentSource: Option[RadioSource],
                                   disabledSources: List[RadioSource],
                                   gotProgress: Boolean):
    /**
      * Returns an updated state with the given progress flag. This is an
      * optimization that prevents creating a new object if the state is not
      * changed.
      *
      * @param f the new progress state
      * @return an instance with the correct progress state
      */
    def updateProgressState(f: Boolean): GuardianState =
      if gotProgress == f then this else copy(gotProgress = f)

  /**
    * A trait defining a factory function for creating new instances of the
    * playback guardian actor.
    */
  trait Factory:
    /**
      * Returns a ''Behavior'' for creating a new instance of this actor class.
      *
      * @param checkInterval     the interval in which playback checks should be
      *                          done
      * @param enabledStateActor the actor to disable or enable sources
      * @param scheduleActor     the scheduler actor
      * @param eventActor        the event manager actor
      * @return the ''Behavior'' to create a new actor instance
      */
    def apply(checkInterval: FiniteDuration,
              enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
              scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
              eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]]):
    Behavior[PlaybackGuardianCommand]

  /**
    * A default [[Factory]] implementation allowing the creation of new actor
    * instances.
    */
  val behavior: Factory = (checkInterval: FiniteDuration, enabledStateActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand], scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand], eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]]) => Behaviors.setup { context =>
    val eventAdapter = context.messageAdapter[RadioEvent] { event => RadioEventReceived(event) }
    eventActor ! EventManagerActor.RegisterListener(eventAdapter)
    val scheduleCommand = ScheduledInvocationActor.typedInvocationCommand(checkInterval, context.self,
      CheckPlaybackProgress)

    def handle(state: GuardianState): Behavior[PlaybackGuardianCommand] =
      Behaviors.receiveMessage:
        case RadioEventReceived(event) =>
          handleEvent(state, event)

        case CheckPlaybackProgress if state.currentSource.isDefined =>
          if !state.gotProgress then
            val source = state.currentSource.get
            context.log.warn("Detected stalled playback for source '{}'.", source)
            enabledStateActor ! RadioControlProtocol.DisableSource(source)
            handle(state.copy(currentSource = None, disabledSources = source :: state.disabledSources))
          else
            scheduleActor ! scheduleCommand
            handle(state.updateProgressState(false))

        case CheckPlaybackProgress =>
          Behaviors.same // Playback has been stopped, so do nothing.

    def handleEvent(state: GuardianState, event: RadioEvent): Behavior[PlaybackGuardianCommand] =
      event match
        case RadioSourceChangedEvent(source, _) =>
          if state.currentSource.isEmpty then
            scheduleActor ! scheduleCommand
          handle(state.copy(currentSource = Some(source)))

        case _: RadioPlaybackProgressEvent =>
          val markedState = state.updateProgressState(true)
          val nextState = if markedState.disabledSources.isEmpty then markedState
          else
            markedState.disabledSources.foreach { source =>
              enabledStateActor ! RadioControlProtocol.EnableSource(source)
            }
            markedState.copy(disabledSources = Nil)
          handle(nextState)

        case _: RadioPlaybackStoppedEvent =>
          handle(state.copy(currentSource = None))

        case _ => Behaviors.same

    val initState = GuardianState(currentSource = None,
      gotProgress = false,
      disabledSources = Nil)
    handle(initState)
  }
