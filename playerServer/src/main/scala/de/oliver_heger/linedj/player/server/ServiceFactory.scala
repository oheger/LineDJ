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

import akka.actor.{ActorRef, ActorSystem, typed}
import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.player.server.EndpointRequestHandlerActor.HandlerReady
import de.oliver_heger.linedj.player.server.ServiceFactory.EndpointRequestHandlerName

import scala.concurrent.{ExecutionContext, Future}

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
    * @param config        the current configuration
    * @param readyListener a listener to notify when the actor is active
    * @return the endpoint request handler actor
    */
  def createEndpointRequestHandler(config: PlayerServerConfig,
                                   readyListener: Option[typed.ActorRef[HandlerReady]] = None): ActorRef =
    val responseTemplate =
      s"http://${EndpointRequestHandlerActor.PlaceHolderAddress}:${config.serverPort}${config.uiPath}"
    val props = EndpointRequestHandlerActor.props(config.lookupMulticastAddress,
      config.lookupPort,
      config.lookupCommand,
      responseTemplate,
      readyListener)
    config.radioPlayerConfig.playerConfig.actorCreator.createClassicActor(props, EndpointRequestHandlerName)

  /**
    * Creates the [[RadioPlayer]] instance based on the given configuration.
    *
    * @param config the [[PlayerServerConfig]]
    * @param system the actor system
    * @return the radio player instance
    */
  def createRadioPlayer(config: PlayerServerConfig)
                       (implicit system: ActorSystem): Future[RadioPlayer] =
    implicit val ec: ExecutionContext = system.dispatcher
    RadioPlayer(config.radioPlayerConfig)
