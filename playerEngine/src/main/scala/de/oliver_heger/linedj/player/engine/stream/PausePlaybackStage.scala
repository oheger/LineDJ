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

package de.oliver_heger.linedj.player.engine.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
  * A module providing a special stage implementation that allows pausing audio
  * playback temporarily.
  *
  * The pause functionality is implemented by a stage that does not let data
  * pass the stream if playback is paused. The current pause status is managed
  * by an actor; by sending specific messages to the actor, playback can be
  * started or stopped.
  */
object PausePlaybackStage {
  /**
    * A trait defining the hierarchy of commands supported by the actor to
    * pause playback.
    */
  sealed trait PausePlaybackCommand

  /**
    * A command processed by the pause playback actor to control the flow of
    * audio data. The pause protocol expects that clients send this message to
    * the actor whenever audio data is to be passed downstream. The actor then
    * responds with a [[PlaybackPossible]] message when playback is not (or no
    * longer) paused. If playback is paused and multiple commands are received
    * with the same ''replyTo'' field, this actor receives only a single
    * notification.
    *
    * @param replyTo the actor to send the response to
    */
  case class WaitForPlaybackPossible(replyTo: ActorRef[PlaybackPossible]) extends PausePlaybackCommand

  /**
    * A command that sets the internal state of the pause playback actor to
    * ''playback possible''. If clients are waiting for a [[PlaybackPossible]]
    * response, they are notified now. Clients that sent a new
    * [[WaitForPlaybackPossible]] message are answered immediately.
    */
  case object StartPlayback extends PausePlaybackCommand

  /**
    * A command that sets the internal state of the pause playback actor to
    * ''playback paused''. Incoming [[WaitForPlaybackPossible]] message will be
    * stored and answered only after receiving a [[StartPlayback]] message.
    */
  case object StopPlayback extends PausePlaybackCommand

  /**
    * A command that can be used to query the current [[PlaybackState]] managed
    * by an actor instance. The actor responds with a [[CurrentPlaybackState]]
    * message.
    *
    * @param replyTo the actor to send the response to
    */
  case class GetCurrentPlaybackState(replyTo: ActorRef[CurrentPlaybackState]) extends PausePlaybackCommand

  /**
    * A command that stops a pause playback actor instance.
    */
  case object Stop extends PausePlaybackCommand

  /**
    * A message the pause playback actor sends to clients when playback is now
    * possible. On receiving this message, audio data can flow through the
    * stream.
    */
  case class PlaybackPossible()

  /**
    * A message the pause playback actor sends to clients as response of a
    * [[GetCurrentPlaybackState]] command. From this message, the current
    * [[PlaybackState]] can be determined.
    *
    * @param state the current [[PlaybackState]]
    */
  case class CurrentPlaybackState(state: PlaybackState)

  /**
    * An enumeration defining the possible values for the internal playback
    * state managed by this actor. This can be used to find out whether
    * playback is currently enabled or paused.
    */
  enum PlaybackState:
    case PlaybackPossible
    case PlaybackPaused
  end PlaybackState

  /**
    * The implicit timeout when waiting for playback to be enabled. Since it is
    * not possible to specify an infinite duration here, just a large value is
    * used (the maximum allowed delay).
    */
  private given waitForPlaybackTimeout: Timeout = Timeout(21474835.seconds)

  /**
    * Returns the behavior for a new instance of the pause playback actor. The
    * actor is initialized with the given [[PlaybackState]].
    *
    * @param initialState the initial [[PlaybackState]] of the actor instance
    * @return the behavior for the new actor instance
    */
  def pausePlaybackActor(initialState: PlaybackState): Behavior[PausePlaybackCommand] =
    handlePausePlaybackCommand(initialState)

  /**
    * Returns a stage that allows pausing playback with the help of the given
    * pause playback actor. If the actor is undefined, the function returns a
    * dummy stage that simply forwards all incoming data. This is useful if
    * pausing playback is not desired for a specific stream.
    *
    * @param optPauseActor the optional actor to control the [[PlaybackState]]
    * @tparam T the type of data processed by the stage
    * @return the new pause playback stage
    */
  def pausePlaybackStage[T](optPauseActor: Option[ActorRef[PausePlaybackCommand]])
                           (using system: ActorSystem[_]): Flow[T, T, NotUsed] =
    given ec: ExecutionContext = system.executionContext

    optPauseActor match
      case Some(pauseActor) =>
        Flow[T].mapAsync(1) { data =>
          val futReply: Future[PlaybackPossible] = pauseActor.ask(ref => WaitForPlaybackPossible(ref))
          futReply.map(_ => data)
        }
      case None =>
        Flow[T].map(identity)

  /**
    * The command handler function of the pause playback actor.
    *
    * @param playbackState the current [[PlaybackState]]
    * @param pendingWaits  set of clients waiting for a notification that 
    *                      playback is possible
    * @return the updated behavior
    */
  private def handlePausePlaybackCommand(playbackState: PlaybackState,
                                         pendingWaits: Set[ActorRef[PlaybackPossible]] = Set.empty):
  Behavior[PausePlaybackCommand] =
    Behaviors.receiveMessage {
      case GetCurrentPlaybackState(replyTo) =>
        replyTo ! CurrentPlaybackState(playbackState)
        Behaviors.same

      case StartPlayback =>
        pendingWaits.foreach(_ ! PlaybackPossible())
        handlePausePlaybackCommand(PlaybackState.PlaybackPossible)

      case StopPlayback =>
        handlePausePlaybackCommand(PlaybackState.PlaybackPaused)

      case WaitForPlaybackPossible(replyTo) =>
        if playbackState == PlaybackState.PlaybackPossible then
          replyTo ! PlaybackPossible()
          Behaviors.same
        else
          handlePausePlaybackCommand(playbackState, pendingWaits + replyTo)

      case Stop =>
        Behaviors.stopped
    }
}
