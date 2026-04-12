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
import org.apache.logging.log4j.LogManager
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import org.apache.pekko.stream.scaladsl.Framing.FramingException

import scala.concurrent.{ExecutionContext, Future}

object Controller extends CloudArchiveModel.CloudArchiveJsonSupport:
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

  /** The logger. */
  private val log = LogManager.getLogger(classOf[Controller])

  /**
    * A global exception handler that deals with invalid JSON in the body of
    * requests.
    */
  private val jsonExceptionHandler = ExceptionHandler:
    case e: FramingException =>
      extractUri: uri =>
        log.error("Error when processing request to {}. JSON body could not be parsed.", uri, e)
        val response = HttpResponse(
          status = StatusCodes.BadRequest,
          entity = "Body contains invalid JSON or no JSON at all."
        )
        complete(response)

  /**
    * Returns the route to query information about pending credentials.
    *
    * @param credentialsManager the object to manage credentials
    * @param ec                 the execution context
    * @return the route to query credentials information
    */
  private def getCredentialsRoute(credentialsManager: CloudArchiveCredentialsManager)
                                 (using ec: ExecutionContext): Route =
    get:
      val futCredentialsInfo = fetchCredentialsInfo(credentialsManager)
      onSuccess(futCredentialsInfo): credentialsInfo =>
        complete(credentialsInfo)

  /**
    * Returns the route to set credentials. This route can be used to unlock
    * cloud archives by providing the corresponding credentials. The body of
    * the request must be a JSON array with objects where each object has the
    * properties ''key'' for the credential key and ''value'' for the
    * corresponding value.
    *
    * @param credentialsManager the object to manage credentials
    * @param ec                 the execution context
    * @return the route to set credentials
    */
  private def putCredentialsRoute(credentialsManager: CloudArchiveCredentialsManager)
                                 (using ec: ExecutionContext): Route =
    put:
      extractRequestEntity: entity =>
        val futSetResult = for
          result <- credentialsManager.setCredentials(entity.dataBytes)
          info <- fetchCredentialsInfo(credentialsManager)
        yield
          CloudArchiveModel.SetCredentialsResponse(
            invalidKeys = result.invalidKeys,
            info = info
          )
        onSuccess(futSetResult): response =>
          complete(response)

  /**
    * Returns the route to query the current status of the managed cloud
    * archives.
    *
    * @param archiveManager the archive manager
    * @param ec             the execution context
    * @return the route to query the archive status
    */
  private def statusRoute(archiveManager: CloudArchiveManager)
                         (using ec: ExecutionContext): Route =
    get:
      val futStatus = archiveManager.archivesState.map: status =>
        CloudArchiveModel.CloudArchiveStateResponse(
          waitingArchives = filterByState(status): (name, state) =>
            state match
              case CloudArchiveManager.CloudArchiveState.Waiting => Some(name)
              case _ => None,
          loadedArchives = filterByState(status): (name, state) =>
            state match
              case _: CloudArchiveManager.CloudArchiveState.Loaded => Some(name)
              case _ => None,
          failedArchives = filterByState(status): (name, state) =>
            state match
              case CloudArchiveManager.CloudArchiveState.Failure(exception, attempts) =>
                val failure = s"${exception.getClass.getSimpleName}: ${exception.getMessage}"
                Some(CloudArchiveModel.FailedArchive(name, failure, attempts))
              case _ => None
        )
      onSuccess(futStatus): status =>
        complete(status)

  /**
    * Obtains information about the currently pending credentials from the
    * given credentials manager.
    *
    * @param credentialsManager the manager for credentials
    * @param ec                 the execution context
    * @return a [[Future]] with the fetched credentials information
    */
  private def fetchCredentialsInfo(credentialsManager: CloudArchiveCredentialsManager)
                                  (using ec: ExecutionContext): Future[CloudArchiveModel.CredentialsInfo] =
    credentialsManager.pendingCredentials.map: credentialKeys =>
      val (fileKeys, archiveKeys) = credentialKeys.partition(
        _.keyType == CloudArchiveCredentialsManager.CredentialKeyType.File
      )
      CloudArchiveModel.CredentialsInfo(
        fileCredentials = fileKeys.map(_.key),
        archiveCredentials = archiveKeys.map(_.key)
      )

  /**
    * Filters the map with cloud archive states using a given filter and
    * transformation function and produces a set with the matched elements.
    * This is used to generate the single properties of archives status query.
    *
    * @param archivesState the object with the archives status
    * @param f             the filter and transformation function
    * @tparam T the result type of the transformation
    * @return a set with the accepted and transformed elements
    */
  private def filterByState[T](archivesState: CloudArchiveManager.ArchivesState)
                              (f: (String, CloudArchiveManager.CloudArchiveState) => Option[T]): Set[T] =
    archivesState.state.foldLeft(Set.empty[T]): (set, t) =>
      f(t._1, t._2) match
        case Some(value) => set + value
        case _ => set
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

  import Controller.*

  override type ArchiveConfig = CloudArchiveServerConfig

  override type CustomContext = CloudArchiveServerContext

  override val defaultConfigFileName: String = "cloud-archive-server-config.xml"

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

  /**
    * @inheritdoc This implementation returns a route exposing additional
    *             endpoints for credentials management and querying the status
    *             of managed cloud archives.
    */
  override def customRoute(context: Context)
                          (using services: ServerController.ServerServices): Option[Route] =
    import context.customContext.*
    val cloudArchiveRoutes = handleExceptions(jsonExceptionHandler):
      concat(
        path("credentials"):
          concat(
            getCredentialsRoute(credentialsManager),
            putCredentialsRoute(credentialsManager)
          ),
        path("archives" / "status"):
          statusRoute(archiveManager)
      )
    Some(cloudArchiveRoutes)
