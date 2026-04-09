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

package de.oliver_heger.linedj.archive.server.local

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.group.ArchiveGroupActor
import de.oliver_heger.linedj.archive.server.ArchiveServerConfig.ConfigLoader
import de.oliver_heger.linedj.archive.server.MediaFileResolver.FileResolverFunc
import de.oliver_heger.linedj.archive.server.ArchiveController
import de.oliver_heger.linedj.archive.server.local.content.ArchiveContentMetadataProcessingListener
import de.oliver_heger.linedj.archiveunion.{MediaUnionActor, MetadataUnionActor, UnionArchiveConfig}
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.actor as classics

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.{Success, Try}

object Controller:
  /**
    * The default configuration passed to the union metadata actor. These 
    * options are not really needed; so, they can be hard-coded.
    */
  private[server] val DefaultUnionArchiveConfig = UnionArchiveConfig(
    metadataUpdateChunkSize = 32,
    initMetadataMaxMsgSize = 128
  )
end Controller

/**
  * Implementation of a [[ServerController]] for the archive server exposing
  * access to local media archives.
  *
  * @param metadataListenerFactory factory for creating the metadata listener
  */
class Controller(metadataListenerFactory: ArchiveContentMetadataProcessingListener.Factory =
                 ArchiveContentMetadataProcessingListener.behavior) extends ArchiveController:
  this: SystemPropertyAccess =>

  import Controller.*
  import MediaArchiveConfigLoaderCC2.given

  override type ArchiveConfig = Seq[MediaArchiveConfig]

  override type CustomContext = Unit

  override def configLoader: ConfigLoader[ArchiveConfig] = config =>
    Try(MediaArchiveConfig.loadMediaArchiveConfigs(config))

  override def createCustomContext(context: ArchiveController.ArchiveServerContext[ArchiveConfig, Unit])
                                  (using services: ServerController.ServerServices): Future[Unit] =
    Future.successful(())

  override def fileResolverFunc(context: Context)
                               (using services: ServerController.ServerServices): FileResolverFunc =
    MediaFileResolverLocal.localFileResolverFunc(context.serverConfig.archiveConfig)

  override def createContext(using services: ServerController.ServerServices): Future[Context] =
    super.createContext.andThen:
      case Success(context) =>
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
          metadataListenerBehavior = metadataListenerFactory(context.contentActor),
          archiveConfigs = context.serverConfig.archiveConfig
        )
        services.managingActorFactory.createClassicActor(propsGroupActor, "archiveGroupActor")
