/*
 * Copyright 2015-2024 The Developers Team.
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
import de.oliver_heger.linedj.player.server.ServiceFactory.{EndpointRequestHandlerName, ServerStartupData, TerminationTimeout, log}
import de.oliver_heger.linedj.utils.ActorManagement
import org.apache.logging.log4j.LogManager
import org.apache.pekko.Done
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object ServiceFactory:
  /** The default name of the request handler actor. */
  final val EndpointRequestHandlerName = "endpointRequestHandlerActor"

  /** The hard timeout when terminating the actor system. */
  private val TerminationTimeout = 5.seconds

  /** The logger. */
  private val log = LogManager.getLogger(classOf[ServiceFactory])

  /**
    * A data class that collects information about a newly started HTTP server.
    * An instance of this class is returned by the function that creates a
    * server. This instance can then be passed to the function that enables the
    * server shutdown.
    *
    * @param binding the [[ServerBinding]] of the new server
    * @param config  the configuration used by the server
    */
  final case class ServerStartupData(binding: ServerBinding,
                                     config: PlayerServerConfig)

/**
  * A factory class for creating several services used by the Player Server
  * application based on the current [[PlayerServerConfig]].
  *
  * @param radioPlayerFactory the factory for creating the [[RadioPlayer]]
  */
class ServiceFactory(radioPlayerFactory: RadioPlayerFactory = new RadioPlayerFactory):
  /**
    * Creates an actor instance that listens for UDP requests for the endpoint
    * URL of the player server.
    *
    * @param config        the current configuration
    * @return the endpoint request handler actor
    */
  def createEndpointRequestHandler(config: PlayerServerConfig): ActorRef =
    val responseTemplate =
      s"http://${EndpointRequestHandlerActor.PlaceHolderAddress}:${config.serverPort}${config.uiPath}"
    val props = EndpointRequestHandlerActor.props(config.lookupMulticastAddress,
      config.lookupPort,
      config.lookupCommand,
      responseTemplate)
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
    radioPlayerFactory.createRadioPlayer(config) map { player =>
      player.addAudioStreamFactory(Mp3AudioStreamFactory)
      player.initRadioSourceConfig(config.sourceConfig)
      player.initMetadataConfig(config.metadataConfig)

      config.initialSource foreach { source =>
        player.switchToRadioSource(source)
        player.startPlayback()
      }
      player
    }

  /**
    * Creates and starts an HTTP server according to the given configuration
    * that operates on the given radio player. The server supports a shutdown
    * command that triggers the provided promise. With this mechanism, the
    * server can be stopped.
    *
    * @param config          the configuration
    * @param radioPlayer     the radio player
    * @param shutdownPromise the promise to trigger shutdown
    * @param system          the actor system
    * @return a [[Future]] with the [[ServerBinding]] object
    */
  def createHttpServer(config: PlayerServerConfig,
                       radioPlayer: RadioPlayer,
                       shutdownPromise: Promise[Done])
                      (implicit system: ActorSystem): Future[ServerStartupData] =
    Http().newServerAt("0.0.0.0", config.serverPort)
      .bind(Routes.route(config, radioPlayer, shutdownPromise))
      .map(binding => ServerStartupData(binding, config))(system.dispatcher)

  /**
    * Enables the system to shutdown gracefully when the given shutdown future
    * completes. This function installs hooks that stop all managed actors and
    * terminate the actor system when both the startup future and the shutdown
    * future are completed. It returns a [[Future]] that completes when the
    * actor system has been terminated. This ''Future'' contains an ''Option''
    * with the shutdown command to be executed.
    *
    * @param startupFuture   the future with the [[ServerStartupData]]
    * @param shutdownFuture  the future to trigger the shutdown
    * @param actorManagement the object to manage actors
    * @param system          the actor system
    * @return a [[Future]] that indicates the termination of the system and the
    *         shutdown command to be executed
    */
  def enableGracefulShutdown(startupFuture: Future[ServerStartupData],
                             shutdownFuture: Future[Done],
                             actorManagement: ActorManagement)
                            (implicit system: ActorSystem): Future[Option[String]] =
    implicit val ec: ExecutionContext = system.dispatcher
    val shutdownStartupFuture = startupFuture.map { startup =>
      val shutdownBinding = startup.binding.addToCoordinatedShutdown(TerminationTimeout)
      startup.copy(binding = shutdownBinding)
    }
    val futCanShutdown = for
      startup <- shutdownStartupFuture
      _ <- shutdownFuture
    yield startup.config

    val promiseTerminated = Promise[Option[String]]()
    futCanShutdown.onComplete { triedConfig =>
      val optShutdownCommand = triedConfig match
        case Failure(exception) =>
          log.error("Error when setting up server.", exception)
          None
        case Success(value) =>
          log.info("Triggering shutdown.")
          value.optShutdownCommand

      log.info("Stopping actors and terminating actor system.")
      actorManagement.stopActors()
      system.terminate() onComplete { _ =>
        promiseTerminated.success(optShutdownCommand)
      }
    }

    promiseTerminated.future
