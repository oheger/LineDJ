/*
 * Copyright 2015-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.radio

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer

/**
  * A message bus listener for events generated by the radio player.
  *
  * This class processes a subset of radio player events by delegating to the
  * [[RadioController]]. This is mainly related to UI updates, but also to
  * error handling when the current radio source crashes.
  *
  * @param controller the radio controller
  * @param player     the radio player
  */
class RadioPlayerEventListener(controller: RadioController, player: RadioPlayer) extends
  MessageBusListener {
  override def receive: Receive = {
    case RadioPlayerEvent(event, radioPlayer) if player == radioPlayer =>
      event match {
        case RadioSourceChangedEvent(source, _) =>
          controller radioSourcePlaybackStarted source
        case RadioSourceReplacementStartEvent(_, replacementSource, _) =>
          controller.replacementSourceStarts(replacementSource)
        case RadioSourceReplacementEndEvent(_, _) =>
          controller.replacementSourceEnds()
        case PlaybackProgressEvent(_, time, _, _) =>
          controller playbackTimeProgress time
        case ev: RadioSourceErrorEvent =>
          controller playbackError ev
        case PlaybackContextCreationFailedEvent(_, _) =>
          controller.playbackContextCreationFailed()
        case PlaybackErrorEvent(_, _) =>
          // handle like a failed context created
          controller.playbackContextCreationFailed()
        case _ => // ignore other events
      }
  }
}
