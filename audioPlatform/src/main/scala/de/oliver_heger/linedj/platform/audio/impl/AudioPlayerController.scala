/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.impl

import akka.actor.Actor
import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer

private class AudioPlayerController(val player: AudioPlayer) extends MessageBusListener {
  /**
    * Returns the function for handling messages published on the message bus.
    *
    * @return the message handling function
    */
  override def receive: Receive = Actor.emptyBehavior
}
