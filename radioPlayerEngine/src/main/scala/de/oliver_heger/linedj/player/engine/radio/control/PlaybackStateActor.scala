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

import de.oliver_heger.linedj.player.engine.radio.RadioSource
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamPlaybackActor
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/**
  * An actor implementation that manages the current playback state, i.e. the
  * [[RadioSource]] selected by the user, the current [[RadioSource]] to be
  * played (which may be a replacement source) and a flag whether playback is
  * currently active.
  *
  * Changes on the playback state are propagated if necessary to a
  * [[RadioStreamPlaybackActor]]. Via the messages supported by this actor,
  * playback of a specific source can be started (n.b. which is not necessarily
  * the source selected by the user; it can well be a temporary replacement
  * source), or stopped.
  *
  * Note: Since the state to be managed is rather simple, there is no
  * associated update service.
  */
object PlaybackStateActor:
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
    * A command to set the [[RadioSource]] that has been selected by the user.
    * This information is stored in the playback state; but it is not directly
    * evaluated. The source that is actually played is set using the
    * [[PlaybackSource]] command.
    *
    * @param source the currently selected [[RadioSource]]
    */
  case class SourceSelected(source: RadioSource) extends PlaybackStateCommand

  /**
    * A command for querying the current playback state.
    *
    * @param replyTo reference to the actor to send the response to
    */
  case class GetPlaybackState(replyTo: ActorRef[CurrentPlaybackState]) extends PlaybackStateCommand

  /**
    * A data class containing information about the current playback state. An
    * instance is sent as response of a [[GetPlaybackState]] request. Note that
    * there is the possible combination that playback is enabled, but no source
    * is available. Since this typically does not make sense in practice, for
    * this combination the playback active flag is set to '''false'''.
    *
    * @param currentSource  an option with the [[RadioSource]] that is
    *                       currently played
    * @param selectedSource an option with the selected [[RadioSource]]
    * @param playbackActive flag whether playback is currently active
    */
  case class CurrentPlaybackState(currentSource: Option[RadioSource],
                                  selectedSource: Option[RadioSource],
                                  playbackActive: Boolean)

  /**
    * A trait that defines a factory function for creating a ''Behavior'' for a
    * new actor instance.
    */
  trait Factory:
    /**
      * Returns a ''Behavior'' to create a new instance of this actor
      * implementation. The function expects the facade actor to be controlled
      * by the new actor instance.
      *
      * @param playbackActor the actor for playing radio sources
      * @return the ''Behavior'' of a new actor instance
      */
    def apply(playbackActor: ActorRef[RadioStreamPlaybackActor.RadioStreamPlaybackCommand]):
    Behavior[PlaybackStateCommand]

  /**
    * A default [[Factory]] instance that can be used to create new instances
    * of this actor implementation.
    */
  final val behavior: Factory = (playbackActor: ActorRef[RadioStreamPlaybackActor.RadioStreamPlaybackCommand]) => {
    val initState = PlaybackState(optSource = None, optSelectedSource = None, optPlayback = None)
    handle(playbackActor, initState)
  }

  /**
    * A data class representing the current playback state.
    *
    * @param optSource         an ''Option'' for the currently played radio
    *                          source
    * @param optSelectedSource an ''Option'' for the currently selected source
    * @param optPlayback       flag whether playback is currently active
    */
  private case class PlaybackState(optSource: Option[RadioSource],
                                   optSelectedSource: Option[RadioSource],
                                   optPlayback: Option[Unit]):
    /**
      * Returns an ''Option'' with the [[RadioSource]] that should now be
      * played. This function takes all criteria into account whether playback
      * is currently possible.
      *
      * @return an ''Option'' with the source to play now
      */
    def playbackSource: Option[RadioSource] =
      for
        _ <- optPlayback
        source <- optSource
      yield source

    /**
      * Updates the flag whether playback is currently active. If there is an
      * actual change in the value of this flag, a defined ''Option'' with the
      * updated state is returned. Otherwise, result is ''None''.
      *
      * @param enabled the new value for the flag
      * @return an ''Option'' with the updated state
      */
    def updatePlayback(enabled: Boolean): Option[PlaybackState] =
      if enabled == optPlayback.isDefined then None
      else Some(copy(optPlayback = if enabled then Some(()) else None))
  end PlaybackState

  /**
    * The main message handling function of this actor.
    *
    * @param playbackActor the playback actor
    * @param state         the current playback state
    * @return the updated ''Behavior''
    */
  private def handle(playbackActor: ActorRef[RadioStreamPlaybackActor.RadioStreamPlaybackCommand],
                     state: PlaybackState): Behavior[PlaybackStateCommand] =
    Behaviors.receiveMessage:
      case PlaybackSource(source) =>
        val nextState = state.copy(optSource = Some(source))
        playSourceIfPossible(playbackActor, nextState)

      case SourceSelected(source) =>
        val nextState = state.copy(optSelectedSource = Some(source))
        handle(playbackActor, nextState)

      case StartPlayback =>
        state.updatePlayback(enabled = true) map { nextState =>
          playSourceIfPossible(playbackActor, nextState)
        } getOrElse Behaviors.same

      case StopPlayback =>
        state.updatePlayback(enabled = false) map { nextState =>
          playbackActor ! RadioStreamPlaybackActor.StopPlayback
          handle(playbackActor, nextState)
        } getOrElse Behaviors.same

      case GetPlaybackState(replyTo) =>
        val playbackActive = state.optPlayback.isDefined && state.optSource.isDefined
        replyTo ! CurrentPlaybackState(state.optSource, state.optSelectedSource, playbackActive)
        Behaviors.same

  /**
    * Checks whether playback is possible based on the given state. If so, the
    * corresponding commands are sent to the playback actor.
    *
    * @param playbackActor the playback actor
    * @param state         the current state
    * @return the updated behavior with the passed state
    */
  private def playSourceIfPossible(playbackActor: ActorRef[RadioStreamPlaybackActor.RadioStreamPlaybackCommand],
                                   state: PlaybackState): Behavior[PlaybackStateCommand] =
    state.playbackSource.map { source =>
      RadioStreamPlaybackActor.PlayRadioSource(source)
    }.foreach(playbackActor.!)

    handle(playbackActor, state)
