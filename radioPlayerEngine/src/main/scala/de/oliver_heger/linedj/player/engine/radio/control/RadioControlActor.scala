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

import de.oliver_heger.linedj.player.engine.radio.RadioSource

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
  /**
    * A message class indicating that playback should switch to another source.
    * This message is sent by internal services when they determine that the
    * currently played source needs to be changed according to the defined
    * constraints. It does not represent a source selection on behalf of the
    * user.
    *
    * @param source the source to be played
    */
  private[control] case class SwitchToSource(source: RadioSource)
}
