/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.server.ArchiveController.ArchiveServerContext
import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.logging.log4j.LogManager
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.{Future, Promise}

object ArchiveController:
  /**
    * The name of a system property that specifies the name of the
    * configuration file to be used. If this property is not defined, the
    * default file name as defined in [[ArchiveServerConfig]] is used.
    */
  final val PropConfigFileName = "configFile"

  /** The logger. */
  private val log = LogManager.getLogger(classOf[ArchiveController])

  /**
    * A data class to represent the context for the archive server.
    *
    * The context contains the configuration of the server and the managed
    * archive(s). It also stores the actor that manages the content of the
    * archive.
    *
    * @param serverConfig  the configuration of the server
    * @param contentActor  the actor managing the content of the archive
    * @param customContext context information for a concrete implementation
    * @tparam CONF   the archive-specific configuration type
    * @tparam CUSTOM the type of the custom context
    */
  final case class ArchiveServerContext[CONF, CUSTOM](serverConfig: ArchiveServerConfig[CONF],
                                                      contentActor:
                                                      ActorRef[ArchiveContentActor.ArchiveContentCommand],
                                                      customContext: CUSTOM)
end ArchiveController

/**
  * A base ''Controller'' trait for archive server applications.
  *
  * This trait provides base functionality to load and parse a server 
  * configuration, to manage the content of an archive, and to expose routes to
  * access this content. It can be extended by concrete implementations that
  * obtain their media files from different sources.
  */
trait ArchiveController extends ServerController:
  this: SystemPropertyAccess =>

  import ArchiveController.*

  /**
    * The type of the concrete configuration to manage the archive used by this
    * controller. This becomes the generic type parameter of the
    * [[ArchiveServerConfig]] for this instance.
    */
  type ArchiveConfig

  /**
    * The type of the custom context used by a concrete controller 
    * implementation. Derived classes can use this to store additional
    * information or service objects.
    */
  type CustomContext

  override type Context = ArchiveServerContext[ArchiveConfig, CustomContext]

  /** The factory for creating a content actor. */
  protected val contentActorFactory: ArchiveContentActor.Factory = ArchiveContentActor.behavior

  /**
    * Returns the object to extract the archive config from the global
    * application configuration. When creating the server context, this loader
    * is used to obtain the archive-specific part of the
    * [[ArchiveServerConfig]].
    *
    * @return the loader for the archive configuration
    */
  def configLoader: ArchiveServerConfig.ConfigLoader[ArchiveConfig]

  /**
    * Returns the function to resolve media files. This is used by endpoints
    * for downloading media files.
    *
    * @param context  the context object
    * @param services the object with server services
    * @return the function to resolve media files in the managed archive(s)
    */
  def fileResolverFunc(context: Context)
                      (using services: ServerController.ServerServices): MediaFileResolver.FileResolverFunc

  /**
    * Creates the object for the custom context. This function is invoked when
    * creating the (base) context. It can be overridden by subclasses to create
    * additional objects required by a concrete server implementation. For this
    * purpose, the context object is passed in that is initialized except for 
    * custom context.
    *
    * @param context  the base context without any custom data
    * @param services the object with server services
    * @return the custom context for this server instance
    */
  def createCustomContext(context: ArchiveServerContext[ArchiveConfig, Unit])
                         (using services: ServerController.ServerServices): Future[CustomContext]

  /**
    * A hook to allow additional routing logic to be injected by a derived
    * class. If a concrete implementation returns a [[Route]] via this
    * function, it is made available under the "/api/archive" endpoint. So, the
    * base API of the archive server can be extended.
    *
    * @param context  the context for this server application
    * @param services the object with server services
    * @return an [[Option]] with an additional [[Route]]
    */
  def customRoute(context: Context)(using services: ServerController.ServerServices): Option[Route] = None

  override def createContext(using services: ServerController.ServerServices): Future[Context] =
    val configFileName = getSystemProperty(PropConfigFileName).getOrElse(ArchiveServerConfig.DefaultConfigFileName)
    log.info("Loading configuration file from '{}'.", configFileName)

    val contentActor = services.managingActorFactory.createTypedActor(contentActorFactory(), "contentActor")
    for
      config <- ArchiveServerConfig(configFileName)(configLoader)
      baseCtx = ArchiveServerContext(config, contentActor, ())
      custom <- createCustomContext(baseCtx)
    yield
      ArchiveServerContext(config, contentActor, custom)

  override def serverParameters(context: Context)
                               (using services: ServerController.ServerServices):
  Future[ServerController.ServerParameters] =
    super.serverParameters(context) map : params =>
      val modifiedBindingParameters = params.bindingParameters.copy(bindPort = context.serverConfig.serverPort)
      params.copy(bindingParameters = modifiedBindingParameters)

  override def route(context: Context, shutdownPromise: Promise[Done])
                    (using services: ServerController.ServerServices): Route =
    Routes.route(
      context.serverConfig.timeout,
      context.contentActor,
      fileResolverFunc(context),
      optCustomRoute = customRoute(context)
    )
