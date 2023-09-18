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

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.client.config.ManagingActorCreator
import de.oliver_heger.linedj.player.server.Server.PropConfigFileName
import de.oliver_heger.linedj.utils.{ActorFactory, ActorManagement, SystemPropertyAccess}
import org.apache.logging.log4j.LogManager

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

object Server:
  /**
    * The name of a system property that specifies the name of the 
    * configuration file to be used. If this property is not defined, the
    * default file name as defined in [[PlayerServerConfig]] is used.
    */
  final val PropConfigFileName = "configFile"

/**
  * A class for creating and running the Player Server.
  *
  * The class uses a [[ServiceFactory]] to set up all required services and to
  * start the HTTP server. It then waits until the server shuts down. Finally,
  * it performs clean up and also terminates the actor system.
  *
  * The player server configuration is loaded from the standard locations
  * supported by Commons Configuration. The name of the configuration file can
  * be specified via the [[Server.PropConfigFileName]] system property.
  *
  * @param serviceFactory the factory for creating services
  * @param system         the actor system
  */
class Server(serviceFactory: ServiceFactory)
            (implicit system: ActorSystem):
  this: SystemPropertyAccess =>

  /** The logger. */
  private val log = LogManager.getLogger(classOf[Server])

  /** The execution context in implicit scope. */
  private implicit val ec: ExecutionContext = system.dispatcher

  /**
    * Starts the server and all active components. The function then waits
    * until the server shuts down (which is triggered by a request to the
    * shutdown endpoint).
    */
  def run(): Unit =
    log.info("Server.run()")

    val actorFactory = new ActorFactory(system)
    val actorManagement = new ActorManagement {}
    val creator = new ManagingActorCreator(actorFactory, actorManagement)
    val shutdownPromise = Promise[Done]()

    val startFuture = (for
      config <- loadServerConfig(creator)
      _ <- startEndpointRequestHandler(config)
      radioPlayer <- serviceFactory.createRadioPlayer(config)
      bindings <- serviceFactory.createHttpServer(config, radioPlayer, shutdownPromise)
    yield bindings.binding) andThen {
      case Success(binding) => log.info("HTTP server is listening on port {}.", binding.localAddress.getPort)
      case Failure(exception) => log.error("Failed to start HTTP server.", exception)
    }

    val terminated = serviceFactory.enableGracefulShutdown(startFuture, shutdownPromise.future, actorManagement)
    Await.ready(terminated, 366.days) // Wait rather long.

    log.info("Server terminated.")

  /**
    * Loads the configuration of the server asynchronously using the name
    * defined by a system property.
    *
    * @param creator the [[ActorCreator]]
    * @return a ''Future'' with the server configuration
    */
  private def loadServerConfig(creator: ActorCreator): Future[PlayerServerConfig] = Future {
    val configName = getSystemProperty(PropConfigFileName) getOrElse PlayerServerConfig.DefaultConfigFileName
    log.info("Loading PlayerServerConfig from '{}'.", configName)
    PlayerServerConfig(configName, null, creator)
  }

  /**
    * Starts the actor handling UDP requests for the endpoint address of the
    * player server.
    *
    * @param config the current server configuration
    * @return a ''Future'' with the actor reference
    */
  private def startEndpointRequestHandler(config: PlayerServerConfig): Future[ActorRef] = Future {
    serviceFactory.createEndpointRequestHandler(config)
  }
