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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.group.ArchiveGroupActor
import de.oliver_heger.linedj.archive.server.content.{ArchiveContentActor, ArchiveContentMetadataProcessingListener}
import de.oliver_heger.linedj.archive.server.model.ArchiveCommands
import de.oliver_heger.linedj.archiveunion.{MediaUnionActor, MetadataUnionActor, UnionArchiveConfig}
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.logging.log4j.LogManager
import org.apache.pekko.{Done, actor as classics}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.server.Route

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}

object Controller:
  /**
    * The name of a system property that specifies the name of the
    * configuration file to be used. If this property is not defined, the
    * default file name as defined in [[ArchiveServerConfig]] is used.
    */
  final val PropConfigFileName = "configFile"

  /**
    * Type alias for the concrete configuration type used by the local archive
    * server.
    */
  type ArchiveServerLocalConfig = ArchiveServerConfig[Seq[MediaArchiveConfig]]

  /** The logger. */
  private val log = LogManager.getLogger(classOf[Controller])

  /**
    * The default configuration passed to the union metadata actor. These 
    * options are not really needed; so, they can be hard-coded.
    */
  private[server] val DefaultUnionArchiveConfig = UnionArchiveConfig(
    metadataUpdateChunkSize = 32,
    initMetadataMaxMsgSize = 128
  )

  /**
    * A data class to represent the context for the archive server.
    *
    * The context now only contains the configuration of the server but also 
    * actors that load and process the archive's content. The load operation is
    * started directly when creating the context.
    *
    * @param serverConfig the configuration of the server
    * @param contentActor the actor managing the content of the archive
    */
  final case class ArchiveServerContext(serverConfig: ArchiveServerLocalConfig,
                                        contentActor: ActorRef[ArchiveCommands.ArchiveQueryCommand])
end Controller

/**
  * Implementation of a [[ServerController]] for the archive server.
  *
  * @param contentActorFactory     factory for creating the content actor
  * @param metadataListenerFactory factory for creating the metadata listener
  */
class Controller(contentActorFactory: ArchiveContentActor.Factory = ArchiveContentActor.behavior,
                 metadataListenerFactory: ArchiveContentMetadataProcessingListener.Factory =
                 ArchiveContentMetadataProcessingListener.behavior) extends ServerController:
  this: SystemPropertyAccess =>

  import Controller.*
  import MediaArchiveConfigLoaderCC2.given 

  override type Context = ArchiveServerContext

  override def createContext(using services: ServerController.ServerServices): Future[ArchiveServerContext] = {
    val configFileName = getSystemProperty(PropConfigFileName).getOrElse(ArchiveServerConfig.DefaultConfigFileName)
    log.info("Loading configuration file from '{}'.", configFileName)

    ArchiveServerConfig(configFileName)(c => MediaArchiveConfig.loadMediaArchiveConfigs(c)) map : config =>
      val contentActor = services.managingActorFactory.createTypedActor(contentActorFactory(), "contentActor")

      val propsMetaUnionActor = classics.Props(classOf[MetadataUnionActor], DefaultUnionArchiveConfig)
      val metadataUnionActor = services.managingActorFactory.createClassicActor(
        propsMetaUnionActor,
        "metadataUnionActor"
      )
      val propsMediaUnionActor = MediaUnionActor(metadataUnionActor)
      val mediaUnionActor = services.managingActorFactory.createClassicActor(propsMediaUnionActor, "mediaUnionActor")
      val propsGroupActor = ArchiveGroupActor(
        mediaUnionActor = mediaUnionActor,
        metadataUnionActor = metadataUnionActor,
        metadataListenerBehavior = metadataListenerFactory(contentActor),
        archiveConfigs = config.archiveConfig
      )
      services.managingActorFactory.createClassicActor(propsGroupActor, "archiveGroupActor")

      ArchiveServerContext(config, contentActor)
  }

  override def serverParameters(context: ArchiveServerContext)
                               (using services: ServerController.ServerServices):
  Future[ServerController.ServerParameters] =
    super.serverParameters(context) map : params =>
      val modifiedBindingParameters = params.bindingParameters.copy(bindPort = context.serverConfig.serverPort)
      params.copy(bindingParameters = modifiedBindingParameters)

  override def route(context: ArchiveServerContext, shutdownPromise: Promise[Done])
                    (using services: ServerController.ServerServices): Route =
    Routes.route(
      context.serverConfig.timeout,
      context.contentActor,
      MediaFileResolver.localFileResolverFunc(context.serverConfig.archiveConfig)
    )
