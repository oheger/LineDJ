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

package de.oliver_heger.linedj.player.server

import akka.actor.ActorSystem
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer

import scala.concurrent.{ExecutionContext, Future}

/**
  * A helper class for creating a [[RadioPlayer]] based on the current
  * configuration.
  */
private class RadioPlayerFactory {
  /**
    * Creates a new [[RadioPlayer]] object asynchronously using the given
    * configuration.
    *
    * @param config the configuration for the player server
    * @param system the current actor system
    * @return a ''Future'' with the new [[RadioPlayer]]
    */
  def createRadioPlayer(config: PlayerServerConfig)
                       (implicit system: ActorSystem): Future[RadioPlayer] =
    implicit val ec: ExecutionContext = system.dispatcher
    RadioPlayer(config.radioPlayerConfig)
}
