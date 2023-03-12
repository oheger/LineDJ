/*
 * Copyright 2015-2023 The Developers Team.
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
import de.oliver_heger.linedj.player.engine.radio._

/**
  * A message bus listener for events generated by the radio player.
  *
  * This class processes a subset of radio player events by delegating to the
  * [[RadioController]] and the [[RadioStatusLineController]]. This is mainly
  * related to UI updates.
  *
  * @param controller           the radio controller
  * @param statusLineController the status line controller
  */
class RadioPlayerEventListener(controller: RadioController,
                               statusLineController: RadioStatusLineController) extends MessageBusListener {
  override def receive: Receive = {
    case RadioPlaybackProgressEvent(source, _, time, _) =>
      controller playbackTimeProgress time
      statusLineController.playbackTimeProgress(source, time)
    case RadioSourceErrorEvent(source, _) =>
      statusLineController.playbackError(source)
    case RadioPlaybackContextCreationFailedEvent(source, _) =>
      statusLineController.playbackError(source)
    case RadioPlaybackErrorEvent(source, _) =>
      statusLineController.playbackError(source)
    case metadataEvent: RadioMetadataEvent =>
      controller.metadataChanged(metadataEvent)
  }
}
