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

import akka.actor.ActorSystem
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.app.support.ActorManagementComponent
import de.oliver_heger.linedj.platform.audio.actors.ManagingActorCreator
import de.oliver_heger.linedj.player.engine.client.config.PlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.client.config.RadioPlayerConfigLoader
import de.oliver_heger.linedj.player.engine.radio.config.RadioPlayerConfig
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * An internally used helper class for creating a [[RadioPlayer]] instance.
  *
  * This class is used during setup of the radio player application to create
  * the actual player object.
  */
private class RadioPlayerFactory {
  /**
    * Creates a ''RadioPlayer'' instance using the provided ''ActorManagement''
    * object as actor factory. The creation of the player is an asynchronous
    * operation; therefore, result is a ''Future''.
    *
    * @param actorManagement the ''ActorManagementComponent''
    * @param system          the ''ActorSystem''
    * @param ec              the ''ExecutionContext''
    * @return a ''Future'' with the newly created ''RadioPlayer''
    */
  def createRadioPlayer(actorManagement: ActorManagementComponent)
                       (implicit system: ActorSystem, ec: ExecutionContext): Future[RadioPlayer] =
    RadioPlayer(createPlayerConfig(actorManagement))

  /**
    * Creates the configuration for the new player instance. This
    * implementation uses some hard-coded values which are appropriate for
    * playback of internet radio. For the properties specific to the radio
    * player, the default values are used.
    *
    * @param actorManagement the ''ActorManagement''
    * @return the player configuration
    */
  private def createPlayerConfig(actorManagement: ActorManagementComponent): RadioPlayerConfig = {
    val creator = new ManagingActorCreator(actorManagement.clientApplicationContext.actorFactory, actorManagement)
    val playerConfig = PlayerConfigLoader.DefaultPlayerConfig.copy(inMemoryBufferSize = 64 * 1024,
      playbackContextLimit = 8192,
      bufferChunkSize = 4096,
      timeProgressThreshold = 100.millis,
      blockingDispatcherName = Some(ClientApplication.BlockingDispatcherName),
      mediaManagerActor = null,
      actorCreator = creator)
    RadioPlayerConfigLoader.DefaultRadioPlayerConfig.copy(playerConfig = playerConfig)
  }
}
