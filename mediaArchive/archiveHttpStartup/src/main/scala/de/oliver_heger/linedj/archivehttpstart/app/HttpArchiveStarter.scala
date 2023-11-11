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

package de.oliver_heger.linedj.archivehttpstart.app

import de.oliver_heger.linedj.archivecommon.download.DownloadMonitoringActor
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader
import de.oliver_heger.linedj.archivehttp.temp.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.archivehttpstart.spi.HttpArchiveProtocolSpec
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import de.oliver_heger.linedj.utils.ActorFactory
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.{ActorRef, ActorSystem}

import java.nio.file.Paths
import java.security.Key
import scala.concurrent.{ExecutionContext, Future}

object HttpArchiveStarter {
  /** The name of the HTTP archive management actor. */
  final val ManagementActorName = "httpArchiveManagementActor"

  /** The name of the download monitoring actor. */
  final val DownloadMonitoringActorName = "downloadMonitoringActor"

  /** The name of the remove file actor. */
  final val RemoveFileActorName = "removeFileActor"

  /** The name of the actor responsible for sending HTTP requests. */
  final val HttpRequestActorName = "http"

  /**
    * Configuration property for the directory for temporary files created
    * during a download operation. If the property is not specified, the
    * OS temp directory is used.
    */
  final val PropTempDirectory = "media.downloadTempDir"

  /** System property for the OS temp directory. */
  private val SysPropTempDir = "java.io.tmpdir"

  /**
    * A data class storing the resources that have been created in order to
    * start an HTTP archive.
    *
    * An object of this class is returned by [[HttpArchiveStarter]] when an
    * archive could be started successfully. This is needed to gracefully
    * shutdown the archive when it is no longer needed.
    *
    * @param actors        a map with the (classic) actors used by the archive
    *                      (keyed by the actor names)
    * @param downloader    the ''MediaDownloader'' used by the archive
    * @param httpActorName the name of the HTTP request actor
    */
  case class ArchiveResources(actors: Map[String, ActorRef],
                              downloader: MediaDownloader,
                              httpActorName: String)

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
  * into generated actor names prevents this. The object to download media
  * files is returned as well as it needs to be properly shutdown when the
  * archive is no longer needed.
  *
  * @param downloaderFactory the object to create the media downloader
  * @param authConfigFactory the factory for the auth configuration
  */
class HttpArchiveStarter(val downloaderFactory: MediaDownloaderFactory,
                         val authConfigFactory: AuthConfigFactory) {

  import HttpArchiveStarter._

  /**
    * Starts up the HTTP archive with the specified settings and returns a
    * ''Future'' with the resources created for this archive.
    *
    * @param unionArchiveActors an object with the actors for the union archive
    * @param archiveData        data for the archive to be started
    * @param config             the configuration
    * @param protocolSpec       the spec for the protocol for this archive
    * @param credentials        the user credentials for the current realm
    * @param optKey             option for the decryption key of an encrypted archive
    * @param actorFactory       the actor factory
    * @param index              an index for unique actor name generation
    * @param clearTemp          flag whether the temp directory should be cleared
    * @param ec                 the execution context
    * @param system             the actor system to materialize streams
    * @return a ''Future'' with the resources created for this archive
    */
  def startup(unionArchiveActors: MediaFacadeActors, archiveData: HttpArchiveData,
              config: Configuration, protocolSpec: HttpArchiveProtocolSpec, credentials: UserCredentials,
              optKey: Option[Key], actorFactory: ActorFactory, index: Int, clearTemp: Boolean)
             (implicit ec: ExecutionContext, system: ActorSystem): Future[ArchiveResources] = for {
    authConf <- authConfigFactory.createAuthConfig(archiveData.realm, credentials)
    httpActorName = archiveActorName(archiveData.shortName, HttpRequestActorName, index)
    downloader <- Future.fromTry(downloaderFactory.createDownloader(protocolSpec, archiveData.config,
      authConf, httpActorName, optKey))
  } yield {
    val archiveConfig = archiveData.config.archiveConfig.copy(downloader = downloader)
    val actors = createArchiveActors(unionArchiveActors, actorFactory, archiveConfig, config,
      archiveData.shortName, index, clearTemp)
    ArchiveResources(actors, downloader, httpActorName)
  }

  /**
    * Creates the actors for the HTTP archive and ensures that anything is
    * initialized.
    *
    * @param unionArchiveActors the object with actors of the union archive
    * @param actorFactory       the actor factory
    * @param archiveConfig      the config of the archive to be created
    * @param config             the original configuration
    * @param shortName          the short name of the archive to be created
    * @param index              an index for unique actor name generation
    * @param clearTemp          the clear temp directory flag
    * @return the map with the actors created by this method
    */
  private def createArchiveActors(unionArchiveActors: MediaFacadeActors,
                                  actorFactory: ActorFactory, archiveConfig: HttpArchiveConfig,
                                  config: Configuration, shortName: String, index: Int, clearTemp: Boolean):
  Map[String, ActorRef] = {
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
      monitoringActor, removeActor), managerName)

    managerActor ! ScanAllMedia
    if (clearTemp) {
      removeActor ! RemoveTempFilesActor.ClearTempDirectory(pathGenerator.rootPath, pathGenerator)
    }

    Map(managerName -> managerActor,
      monitorName -> monitoringActor,
      removeName -> removeActor)
  }
}
