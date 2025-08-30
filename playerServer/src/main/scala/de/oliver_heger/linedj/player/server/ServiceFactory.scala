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

package de.oliver_heger.linedj.player.server

import de.oliver_heger.linedj.player.engine.mp3.Mp3AudioStreamFactory
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.server.common.ServerRunner
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{ExecutionContext, Future}

/**
  * A factory class for creating several services used by the Player Server
  * application based on the current [[PlayerServerConfig]].
  *
  * @param radioPlayerFactory the factory for creating the [[RadioPlayer]]
  */
class ServiceFactory(radioPlayerFactory: RadioPlayerFactory = new RadioPlayerFactory):
  /**
    * Creates a [[ServerRunner]] for launching the player server.
    *
    * @param system the actor system
    * @return the new [[ServerRunner]] instance
    */
  def createServerRunner()(using system: ActorSystem): ServerRunner = new ServerRunner

  /**
    * Creates the [[RadioPlayer]] instance based on the given configuration.
    *
    * @param config the [[PlayerServerConfig]]
    * @param system the actor system
    * @return the radio player instance
    */
  def createRadioPlayer(config: PlayerServerConfig)
                       (using system: ActorSystem): Future[RadioPlayer] =
    given ExecutionContext = system.dispatcher

    radioPlayerFactory.createRadioPlayer(config) map : player =>
      player.addAudioStreamFactory(new Mp3AudioStreamFactory)
      player.initRadioSourceConfig(config.sourceConfig)
      player.initMetadataConfig(config.metadataConfig)

      config.initialSource foreach : source =>
        player.switchToRadioSource(source)
        player.startPlayback()
      player
