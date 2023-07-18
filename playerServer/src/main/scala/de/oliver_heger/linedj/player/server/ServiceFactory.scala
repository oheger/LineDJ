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

import akka.actor.{ActorRef, typed}
import de.oliver_heger.linedj.player.server.EndpointRequestHandlerActor.HandlerReady
import de.oliver_heger.linedj.player.server.ServiceFactory.EndpointRequestHandlerName

object ServiceFactory:
  /** The default name of the request handler actor. */
  final val EndpointRequestHandlerName = "endpointRequestHandlerActor"

/**
  * A factory class for creating several services used by the Player Server
  * application based on the current [[PlayerServerConfig]].
  */
class ServiceFactory:
  /**
    * Creates an actor instance that listens for UDP requests for the endpoint
    * URL of the player server.
    *
    * @param config         the current configuration
    * @param lookupResponse the response for incoming requests
    * @param readyListener  a listener to notify when the actor is active
    * @return the endpoint request handler actor
    */
  def createEndpointRequestHandler(config: PlayerServerConfig,
                                   lookupResponse: String,
                                   readyListener: Option[typed.ActorRef[HandlerReady]] = None): ActorRef =
    val props = EndpointRequestHandlerActor.props(config.lookupMulticastAddress,
      config.lookupPort,
      config.lookupCommand,
      lookupResponse,
      readyListener)
    config.radioPlayerConfig.playerConfig.actorCreator.createClassicActor(props, EndpointRequestHandlerName)

