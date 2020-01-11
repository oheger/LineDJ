/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio

import java.time.LocalDateTime

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}

/**
  * Event class reporting a change of the current state of the central audio
  * player instance.
  *
  * Interested components can register themselves as consumers for events of
  * this type by sending a corresponding ''ConsumerRegistration''. They are
  * then notified whenever the state of the audio player changes.
  *
  * An event instance contains the updated state of the audio player and the
  * time when the last state change occurred.
  *
  * @param state the current state of the audio player
  * @param time  the time when this state became the current one
  */
case class AudioPlayerStateChangedEvent(state: AudioPlayerState,
                                        time: LocalDateTime = LocalDateTime.now())

/**
  * A message class representing a consumer registration for audio player state
  * change events.
  *
  * By publishing a message of this type on the system message bus, a component
  * can register itself as consumer for such change events.
  *
  * @param id       the ID of the consumer component
  * @param callback the consumer function
  */
case class AudioPlayerStateChangeRegistration(override val id: ComponentID,
                                              override val callback:
                                              ConsumerFunction[AudioPlayerStateChangedEvent])
  extends ConsumerRegistration[AudioPlayerStateChangedEvent] {
  override def unRegistration: AnyRef = AudioPlayerStateChangeUnregistration(id)
}

/**
  * A message class representing the removal of a registration for audio player
  * state change events.
  *
  * @param id the ID of the consumer component
  */
case class AudioPlayerStateChangeUnregistration(id: ComponentID)
