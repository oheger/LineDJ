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
import de.oliver_heger.linedj.server.common
import de.oliver_heger.linedj.server.common.ConfigSupport.ConfigLoader
import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.server.common.{ConfigSupport, ServerController}
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.{Future, Promise}

object ArchiveController:
  /**
    * A data class to represent the custom context for the archive server.
    *
    * The context contains the actor that manages the content of the archive
    * plus the context of a specific archive implementation.
    *
    * @param contentActor   the actor managing the content of the archive
    * @param config         the config for this server
    * @param archiveContext context information for a concrete implementation
    * @tparam ARCH_CONF the type of the archive configuration
    * @tparam ARCH_CTX  the type of the archive context
    */
  final case class ArchiveServerContext[ARCH_CONF, ARCH_CTX](contentActor:
                                                             ActorRef[ArchiveContentActor.ArchiveContentCommand],
                                                             config: ArchiveServerConfig[ARCH_CONF],
                                                             archiveContext: ARCH_CTX)
end ArchiveController

/**
  * A base ''Controller'' trait for archive server applications.
  *
  * This trait provides base functionality to manage the content of an archive
  * and to expose routes to access this content. It can be extended by concrete
  * implementations that obtain their media files from different sources.
  */
trait ArchiveController extends ServerController, ConfigSupport:
  this: SystemPropertyAccess =>

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
  type ArchiveContext

  override type CustomContext = ArchiveServerContext[ArchiveConfig, ArchiveContext]

  override type CustomConfig = ArchiveServerConfig[ArchiveConfig]

  /** The factory for creating a content actor. */
  protected val contentActorFactory: ArchiveContentActor.Factory = ArchiveContentActor.behavior

  override def configLoader: ConfigLoader[CustomConfig] = config =>
    ArchiveServerConfig(config)(archiveConfigLoader)

  /**
    * Returns the object to extract the archive config from the global
    * application configuration. When creating the server context, this loader
    * is used to obtain the archive-specific part of the
    * [[ArchiveServerConfig]].
    *
    * @return the loader for the archive configuration
    */
  def archiveConfigLoader: ConfigSupport.ConfigLoader[ArchiveConfig]

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
    * Creates the object for the custom archive context. This function is 
    * invoked when creating the (base) context. It can be overridden by 
    * subclasses to create additional objects required by a concrete server
    * implementation. For this purpose, all relevant data that is available at
    * this point of time is passed in.
    *
    * @param context  the base context without any custom data
    * @param services the object with server services
    * @return the custom context for this server instance
    */
  def createArchiveContext(context: ArchiveServerContext[ArchiveConfig, Unit])
                          (using services: ServerController.ServerServices): Future[ArchiveContext]

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

  override def createCustomContext(context: ConfigSupport.ConfigSupportContext[CustomConfig, Unit])
                                  (using services: ServerController.ServerServices):
  Future[ArchiveServerContext[ArchiveConfig, ArchiveContext]] =
    val contentActor = services.managingActorFactory.createTypedActor(contentActorFactory(), "contentActor")
    val baseCtx = ArchiveServerContext(contentActor, context.config, ())
    createArchiveContext(baseCtx) map : archiveContext =>
      ArchiveServerContext(contentActor, context.config, archiveContext)

  override def route(context: Context, shutdownPromise: Promise[Done])
                    (using services: ServerController.ServerServices): Route =
    Routes.route(
      context.config.actorTimeout,
      context.context.contentActor,
      fileResolverFunc(context),
      optCustomRoute = customRoute(context)
    )
