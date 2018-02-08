/*
 * Copyright 2015-2018 The Developers Team.
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

import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.facade.RadioPlayer

/**
  * An internally used helper class for creating a [[RadioPlayer]] instance.
  *
  * This class is used during setup of the radio player application to create
  * the actual player object.
  */
private class RadioPlayerFactory {
  /**
    * Creates a ''RadioPlayer'' instance using the provided ''ActorManagement''
    * object as actor factory.
    *
    * @param actorManagement the ''ActorManagement''
    * @return the newly created ''RadioPlayer''
    */
  def createRadioPlayer(actorManagement: ActorManagement): RadioPlayer =
    RadioPlayer(createPlayerConfig(actorManagement))

  /**
    * Creates the configuration for the new player instance. This
    * implementation uses some hard-coded values which are appropriate for
    * playback of internet radio.
    *
    * @param actorManagement the ''ActorManagement''
    * @return the player configuration
    */
  private def createPlayerConfig(actorManagement: ActorManagement):
  PlayerConfig =
    PlayerConfig(inMemoryBufferSize = 64 * 1024, playbackContextLimit = 8192,
      bufferChunkSize = 4096,
      blockingDispatcherName = Some(ClientApplication.BlockingDispatcherName),
      mediaManagerActor = null, actorCreator = actorManagement.createAndRegisterActor)
}
