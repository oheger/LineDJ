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

import akka.{actor => classic}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import de.oliver_heger.linedj.player.engine.actors.{DelayActor, PlaybackActor, PlayerFacadeActor}
import de.oliver_heger.linedj.player.engine.radio.RadioSource

/**
  * An actor implementation that manages the current playback state, i.e. the
  * [[RadioSource]] to be played and a flag whether playback is currently
  * active.
  *
  * Changes on the playback state are propagated if necessary to a
  * [[PlayerFacadeActor]]. Via the messages supported by this actor, playback
  * of a specific source can be started (n.b. which is not necessarily the
  * source selected by the user; it can well be a temporary replacement
  * source), or stopped.
  *
  * Note: Since the state to be managed is rather simple, there is no
  * associated update service.
  */
object PlaybackStateActor {
  /**
    * The base trait of the commands supported by this actor.
    */
  sealed trait PlaybackStateCommand

  /**
    * A command to start the playback of the [[RadioSource]] in the current
    * playback state if any. If there is no such source, playback will start
    * only it has been set.
    */
  case object StartPlayback extends PlaybackStateCommand

  /**
    * A command to stop playback. The current radio stream - if any - is
    * canceled.
    */
  case object StopPlayback extends PlaybackStateCommand

  /**
    * A command to set the [[RadioSource]] to be played. If playback is already
    * active, a stream for this source is opened immediately and played.
    * Otherwise, playback starts after receiving a [[StartPlayback]] command.
    *
    * @param source the [[RadioSource]] to be played
    */
  case class PlaybackSource(source: RadioSource) extends PlaybackStateCommand

  /**
    * A trait that defines a factory function for creating a ''Behavior'' for a
    * new actor instance.
    */
  trait Factory {
    /**
      * Returns a ''Behavior'' to create a new instance of this actor
      * implementation. The function expects the facade actor to be controlled
      * by the new actor instance.
      *
      * @param facadeActor the player facade actor
      * @return the ''Behavior'' of a new actor instance
      */
    def apply(facadeActor: classic.ActorRef): Behavior[PlaybackStateCommand]
  }

  /**
    * A default [[Factory]] instance that can be used to create new instances
    * of this actor implementation.
    */
  final val behavior: Factory = (facadeActor: classic.ActorRef) => {
    val initState = PlaybackState(optSource = None, optPlayback = None, needsReset = false)
    handle(facadeActor, initState)
  }

  /**
    * A data class representing the current playback state.
    *
    * @param optSource   an ''Option'' for the currently played radio source
    * @param optPlayback flag whether playback is currently active
    * @param needsReset  flag whether the engine needs to be reset
    */
  private case class PlaybackState(optSource: Option[RadioSource],
                                   optPlayback: Option[Unit],
                                   needsReset: Boolean) {
    /**
      * Returns an ''Option'' with the [[RadioSource]] that should now be
      * played. This function takes all criteria into account whether playback
      * is currently possible.
      *
      * @return an ''Option'' with the source to play now
      */
    def playbackSource: Option[RadioSource] =
      for {
        _ <- optPlayback
        source <- optSource
      } yield source

    /**
      * Updates the flag whether playback is currently active. If there is an
      * actual change in the value of this flag, a defined ''Option'' with the
      * updated state is returned. Otherwise, result is ''None''.
      *
      * @param enabled the new value for the flag
      * @return an ''Option'' with the updated state
      */
    def updatePlayback(enabled: Boolean): Option[PlaybackState] =
      if (enabled == optPlayback.isDefined) None
      else Some(copy(optPlayback = if (enabled) Some(()) else None))

    /**
      * Returns an instance with the reset flag set.
      *
      * @return the updated instance
      */
    def resetRequired(): PlaybackState = if (needsReset) this else copy(needsReset = true)
  }

  /**
    * The main message handling function of this actor.
    *
    * @param facadeActor the facade actor
    * @param state       the current playback state
    * @return the updated ''Behavior''
    */
  private def handle(facadeActor: classic.ActorRef, state: PlaybackState): Behavior[PlaybackStateCommand] =
    Behaviors.receiveMessage {
      case PlaybackSource(source) =>
        val nextState = state.copy(optSource = Some(source))
        playSourceIfPossible(facadeActor, nextState)

      case StartPlayback =>
        state.updatePlayback(enabled = true) map { nextState =>
          playSourceIfPossible(facadeActor, nextState)
        } getOrElse Behaviors.same

      case StopPlayback =>
        state.updatePlayback(enabled = false) map { nextState =>
          val dispatch = PlayerFacadeActor.Dispatch(PlaybackActor.StopPlayback, PlayerFacadeActor.TargetPlaybackActor)
          facadeActor ! DelayActor.Propagate(dispatch, facadeActor, DelayActor.NoDelay)
          handle(facadeActor, nextState)
        } getOrElse Behaviors.same
    }

  /**
    * Checks whether playback is possible based on the given state. If so, the
    * corresponding messages are sent to the facade actor. If a source has been
    * played before, the engine needs to be reset.
    *
    * @param facadeActor the facade actor
    * @param state       the current state
    * @return the updated behavior with the passed state
    */
  private def playSourceIfPossible(facadeActor: classic.ActorRef, state: PlaybackState):
  Behavior[PlaybackStateCommand] = {
    val nextState = state.playbackSource map { source =>
      val startMessages = List(
        (PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader()), facadeActor),
        (PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor), facadeActor)
      )
      val resetMessage = if (state.needsReset)
        (PlayerFacadeActor.ResetEngine, facadeActor) :: startMessages
      else startMessages

      facadeActor ! DelayActor.Propagate(resetMessage, DelayActor.NoDelay)
      state.resetRequired()
    } getOrElse state

    handle(facadeActor, nextState)
  }
}
