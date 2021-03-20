/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart.app

import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.archivecommon.download.DownloadMonitoringActor
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig.AuthConfigureFunc
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.archivehttp.{HttpArchiveManagementActor, HttpAuthFactory}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.Configuration

import java.nio.file.Paths
import java.security.Key
import scala.concurrent.{ExecutionContext, Future}

object HttpArchiveStarter {
  /** The name of the HTTP archive management actor. */
  val ManagementActorName = "httpArchiveManagementActor"

  /** The name of the download monitoring actor. */
  val DownloadMonitoringActorName = "downloadMonitoringActor"

  /** The name of the remove file actor. */
  val RemoveFileActorName = "removeFileActor"

  /**
    * Configuration property for the directory for temporary files created
    * during a download operation. If the property is not specified, the
    * OS temp directory is used.
    */
  val PropTempDirectory = "media.downloadTempDir"

  /** System property for the OS temp directory. */
  private val SysPropTempDir = "java.io.tmpdir"

  /**
    * Generates the name of an actor for an HTTP archive based on the short
    * name for the target archive and the provided suffix.
    *
    * @param arcShortName the short name of the HTTP archive
    * @param suffix       the suffix, the actual actor name
    * @param index        an index to make actor names unique
    * @return the resulting full actor name
    */
  def archiveActorName(arcShortName: String, suffix: String, index: Int): String =
    s"$arcShortName${index}_$suffix"

  /**
    * Creates the ''TempPathGenerator'' to be used by the archive. Makes sure
    * that the correct path for temporary files is used: it is either
    * specified in the configuration or defaults to the OS temp directory.
    *
    * @param config the configuration
    * @return the ''TempPathGenerator''
    */
  private def createPathGenerator(config: Configuration): TempPathGenerator =
    TempPathGenerator(Paths get config.getString(PropTempDirectory,
      System.getProperty(SysPropTempDir)))
}

/**
  * A helper class that takes care that an HTTP archive is started correctly.
  *
  * This class externalizes the logic to start an HTTP archive as defined by a
  * [[HttpArchiveData]] object. It extracts the configuration settings, creates
  * all required actors, and initiates a media scan operation.
  *
  * The creation method returns a map with all actors that have been created
  * for the archive. The keys of the map are the names of the actors. Because
  * multiple archives can be active in parallel actor names have to be
  * generated dynamically. The short name of an HTTP archive is included into
  * actor names. In addition, a numeric index is expected as argument to
  * guarantee uniqueness of actor names. This is actually needed to deal with a
  * race condition: When the user updates the credentials of an archive, the
  * corresponding actors are stopped and new ones are created. As stopping
  * actors is an asynchronous operation, it can happen that the creation of new
  * actors fail because names are already in use. The numeric index integrated
  * into generated actor names prevents this.
  *
  * @param authFactory the factory for creating the auth configuration
  */
class HttpArchiveStarter(val authFactory: HttpAuthFactory) {
  /**
    * Creates a new instance of ''HttpArchiveStarter'' with a default
    * ''HttpAuthFactory''.
    */
  def this() = this(HttpArchiveManagementActor)

  import HttpArchiveStarter._

  /**
    * Starts up the HTTP archive with the specified settings and returns a
    * ''Future'' with the actors that have been created.
    *
    * @param unionArchiveActors an object with the actors for the union archive
    * @param archiveData        data for the archive to be started
    * @param config             the configuration
    * @param protocol           the HTTP protocol for the archive
    * @param credentials        the user credentials for the current realm
    * @param optKey             option for the decryption key of an encrypted archive
    * @param actorFactory       the actor factory
    * @param index              an index for unique actor name generation
    * @param clearTemp          flag whether the temp directory should be cleared
    * @param ec                 the execution context
    * @param system             the actor system to materialize streams
    * @return a map of the actors created; keys are the names of
    *         the actor instances
    */
  def startup(unionArchiveActors: MediaFacadeActors, archiveData: HttpArchiveData,
              config: Configuration, protocol: HttpArchiveProtocol, credentials: UserCredentials, optKey: Option[Key],
              actorFactory: ActorFactory, index: Int, clearTemp: Boolean)
             (implicit ec: ExecutionContext, system: ActorSystem): Future[Map[String, ActorRef]] =
    for {
      archiveUri <- Future.fromTry(protocol.generateArchiveUri(archiveData.config.archiveURI.toString()))
      authFunc <- fetchAuthFunc(archiveData.realm, credentials)
    } yield {
      val archiveConfig = archiveData.config.copy(archiveURI = archiveUri, protocol = protocol, authFunc = authFunc)
      createArchiveActors(unionArchiveActors, actorFactory, archiveConfig, config,
        optKey, archiveData.shortName, index, clearTemp)
    }

  /**
    * Creates the actors for the HTTP archive and ensures that anything is
    * initialized.
    *
    * @param unionArchiveActors the object with actors of the union archive
    * @param actorFactory       the actor factory
    * @param archiveConfig      the config of the archive to be created
    * @param config             the original configuration
    * @param optKey             option for the decryption key of an encrypted archive
    * @param shortName          the short name of the archive to be created
    * @param index              an index for unique actor name generation
    * @param clearTemp          the clear temp directory flag
    * @return the map with the actors created by this method
    */
  private def createArchiveActors(unionArchiveActors: MediaFacadeActors,
                                  actorFactory: ActorFactory, archiveConfig: HttpArchiveConfig,
                                  config: Configuration, optKey: Option[Key], shortName: String,
                                  index: Int, clearTemp: Boolean): Map[String, ActorRef] = {
    def actorName(n: String): String = archiveActorName(shortName, n, index)

    val managerName = actorName(ManagementActorName)
    val monitorName = actorName(DownloadMonitoringActorName)
    val removeName = actorName(RemoveFileActorName)
    val pathGenerator = createPathGenerator(config)
    val removeActor = actorFactory.createActor(
      RemoveTempFilesActor(ClientApplication.BlockingDispatcherName), removeName)
    val monitoringActor = actorFactory.createActor(
      DownloadMonitoringActor(archiveConfig.downloadConfig), monitorName)
    val managerActor = actorFactory.createActor(HttpArchiveManagementActor(archiveConfig,
      pathGenerator, unionArchiveActors.mediaManager, unionArchiveActors.metaDataManager,
      monitoringActor, removeActor, optKey), managerName)

    managerActor ! ScanAllMedia
    if (clearTemp) {
      removeActor ! RemoveTempFilesActor.ClearTempDirectory(pathGenerator.rootPath, pathGenerator)
    }

    Map(managerName -> managerActor,
      monitorName -> monitoringActor,
      removeName -> removeActor)
  }

  /**
    * Obtains the correct function to configure the authentication mechanism
    * based on the realm associated with the current archive.
    *
    * @param realm       the realm
    * @param credentials the credentials of the realm
    * @param ec          the execution context
    * @param system      the actor system to materialize streams
    * @return a ''Future'' with the function to configure authentication
    */
  private def fetchAuthFunc(realm: ArchiveRealm, credentials: UserCredentials)
                           (implicit ec: ExecutionContext, system: ActorSystem): Future[AuthConfigureFunc] =
    realm match {
      case _: BasicAuthRealm =>
        authFactory.basicAuthConfigureFunc(credentials)
      case oauthRealm: OAuthRealm =>
        authFactory.oauthConfigureFunc(oauthRealm.createIdpConfig(credentials.password))
    }
}
