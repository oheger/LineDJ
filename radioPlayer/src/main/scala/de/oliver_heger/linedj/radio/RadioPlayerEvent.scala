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

package de.oliver_heger.linedj.radio

import de.oliver_heger.linedj.player.engine.PlayerEvent
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer

/**
  * A data class representing an event fired by the radio player.
  *
  * This class wraps the player event and also contains a reference to the
  * player which is the source of the event. Wrapping the original event makes
  * sense because it is published via the message bus. As there may be
  * multiple players, it is not possible from the original event to determine
  * the source of the event.
  *
  * @param event  the wrapped event from the player
  * @param player the player which is the source of the event
  */
case class RadioPlayerEvent(event: PlayerEvent, player: RadioPlayer)
