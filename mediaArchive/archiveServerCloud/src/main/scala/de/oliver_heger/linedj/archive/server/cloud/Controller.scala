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

package de.oliver_heger.linedj.archive.server.cloud

import com.github.cloudfiles.core.http.HttpRequestSender.FailedResponseException
import com.github.cloudfiles.core.http.factory.HttpRequestSenderFactoryImpl
import de.oliver_heger.linedj.archive.cloud.DefaultCloudFileDownloaderFactory
import de.oliver_heger.linedj.archive.cloud.auth.Credentials.queryCredentialTimeout
import de.oliver_heger.linedj.archive.cloud.auth.DefaultAuthConfigFactory
import de.oliver_heger.linedj.archive.cloud.auth.oauth.OAuthStorageServiceImpl
import de.oliver_heger.linedj.archive.server.ArchiveController
import de.oliver_heger.linedj.archive.server.ArchiveServerConfig.ConfigLoader
import de.oliver_heger.linedj.archive.server.MediaFileResolver.{FileResolverFunc, UnresolvableFileException}
import de.oliver_heger.linedj.archive.server.cloud.Controller.CloudArchiveServerContext
import de.oliver_heger.linedj.server.common.ServerController
import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.pekko.http.scaladsl.model.StatusCodes

import scala.concurrent.Future

object Controller:
  /**
    * A data class representing the custom context of the cloud archive server
    * application. This class holds components that implement specific
    * functionality of this application.
    *
    * @param archiveManager     the archive manager
    * @param credentialsManager the manager for credentials
    */
  final case class CloudArchiveServerContext(archiveManager: CloudArchiveManager,
                                             credentialsManager: CloudArchiveCredentialsManager)
end Controller

/**
  * Implementation of an [[ArchiveController]] for the cloud archive server
  * application. This controller creates the components responsible for
  * managing the credentials of cloud archives and loading the archive data
  * once the credentials become available.
  *
  * @param credentialsManagerFactory the factory to create the credential
  *                                  manager
  * @param archiveManagerFactory     the factory to create the archive manager
  */
class Controller(credentialsManagerFactory: CloudArchiveCredentialsManager.Factory =
                 CloudArchiveCredentialsManager.newInstance,
                 archiveManagerFactory: CloudArchiveManager.Factory =
                 CloudArchiveManager.newInstance) extends ArchiveController:
  this: SystemPropertyAccess =>
  override type ArchiveConfig = CloudArchiveServerConfig

  override type CustomContext = CloudArchiveServerContext

  override def configLoader: ConfigLoader[ArchiveConfig] =
    CloudArchiveServerConfig.parseConfig

  override def fileResolverFunc(context: Context)
                               (using services: ServerController.ServerServices): FileResolverFunc =
    (id, downloadInfo) =>
      context.customContext.archiveManager.archivesState flatMap : state =>
        state.state(downloadInfo.archiveName) match
          case CloudArchiveManager.CloudArchiveState.Loaded(downloader) =>
            downloader.loadMediaFile(downloadInfo.fileUri).recoverWith:
              case e: FailedResponseException if e.response.status == StatusCodes.NotFound =>
                Future.failed(UnresolvableFileException(id))
          case _ =>
            // This should normally not happen; if the archive has not been loaded, there cannot be a
            // download info object pointing to the archive.
            Future.failed(new IllegalStateException(s"Archive '${downloadInfo.archiveName}' is not in Loaded state."))

  override def createCustomContext(context: ArchiveController.ArchiveServerContext[ArchiveConfig, Unit])
                                  (using services: ServerController.ServerServices):
  Future[CustomContext] = Future:
    val credentialsManager = credentialsManagerFactory(
      credentialDirectory = context.serverConfig.archiveConfig.credentialsDirectory,
      factory = services.managingActorFactory
    )

    val authFactory = new DefaultAuthConfigFactory(
      storageService = OAuthStorageServiceImpl,
      storagePath = context.serverConfig.archiveConfig.credentialsDirectory,
      resolverFunc = credentialsManager.resolverFunc
    )
    val downloaderFactory = new DefaultCloudFileDownloaderFactory(authFactory, HttpRequestSenderFactoryImpl)
    val archiveManager = archiveManagerFactory(
      actorFactory = services.managingActorFactory,
      contentActor = context.contentActor,
      config = context.serverConfig.archiveConfig,
      downloaderFactory = downloaderFactory,
      credentialSetter = credentialsManager.setter,
      contentLoader = new CloudArchiveContentLoader
    )

    CloudArchiveServerContext(archiveManager, credentialsManager)
