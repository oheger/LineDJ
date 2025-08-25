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

import de.oliver_heger.linedj.player.engine.radio.facade.RadioPlayer
import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.server.common.{ServerController, ServerLocator}
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.logging.log4j.LogManager
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.{Future, Promise}
import scala.sys.process.Process

object Controller:
  /**
    * The name of a system property that specifies the name of the
    * configuration file to be used. If this property is not defined, the
    * default file name as defined in [[PlayerServerConfig]] is used.
    */
  final val PropConfigFileName = "configFile"

  /** The logger. */
  private val log = LogManager.getLogger(classOf[Controller])

  /**
    * A data class representing the context for the player server. It contains
    * the objects the server requires to function properly.
    *
    * @param config      the server configuration
    * @param radioPlayer the radio player
    */
  final case class PlayerServerContext(config: PlayerServerConfig,
                                       radioPlayer: RadioPlayer)
end Controller

/**
  * A [[ServerController]] implementation for the player server.
  *
  * @param serviceFactory the factory for creating services consumed by this
  *                       controller
  */
class Controller(serviceFactory: ServiceFactory) extends ServerController:
  this: SystemPropertyAccess =>

  import Controller.*

  override type Context = PlayerServerContext

  override def createContext(using services: ServerController.ServerServices): Future[PlayerServerContext] =
    for
      config <- loadServerConfig
      player <- serviceFactory.createRadioPlayer(config)
    yield PlayerServerContext(config, player)

  override def serverParameters(context: PlayerServerContext)
                               (using services: ServerController.ServerServices):
  Future[ServerController.ServerParameters] =
    import context.config.*
    val parameters = ServerController.ServerParameters(
      bindingParameters = ServerController.DefaultServerParameters.bindingParameters.copy(bindPort = serverPort),
      optLocatorParams = Some(
        ServerLocator.LocatorParams(
          multicastAddress = lookupMulticastAddress,
          port = lookupPort,
          requestCode = lookupCommand,
          responseTemplate = s"http://${ServerLocator.PlaceHolderAddress}:$serverPort$uiPath"
        )
      )
    )
    Future.successful(parameters)

  override def route(context: PlayerServerContext, shutdownPromise: Promise[Done])
                    (using services: ServerController.ServerServices): Route = ???

  /**
    * @inheritdoc This implementation executes the shutdown command if it is
    *             defined in the configuration.
    */
  override def afterShutdown(context: PlayerServerContext): Unit =
    context.config.optShutdownCommand foreach : command =>
      log.info("Executing shutdown command: '{}'.", command)
      Process(command).run()

  /**
    * Loads the configuration of the server asynchronously using the name
    * defined by a system property.
    *
    * @param services the object with services
    * @return a [[Future]] with the server configuration
    */
  private def loadServerConfig(using services: ServerController.ServerServices): Future[PlayerServerConfig] = Future:
    val configName = getSystemProperty(PropConfigFileName) getOrElse PlayerServerConfig.DefaultConfigFileName
    log.info("Loading PlayerServerConfig from '{}'.", configName)
    PlayerServerConfig(configName, null, services.managingActorFactory)
