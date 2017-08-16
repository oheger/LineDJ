/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archivehttpstart

import java.nio.file.Paths

import akka.actor.ActorRef
import de.oliver_heger.linedj.archivecommon.download.{DownloadConfig, DownloadMonitoringActor}
import de.oliver_heger.linedj.archivehttp.HttpArchiveManagementActor
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.impl.download.{RemoveTempFilesActor, TempPathGenerator}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.comm.ActorFactory
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.Configuration

import scala.util.Try

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
  val PropTempDirectory = "media.http.tempDir"

  /** System property for the OS temp directory. */
  private val SysPropTempDir = "java.io.tmpdir"

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
  * ''Configuration'' object. It extracts the configuration settings, creates
  * all required actors, and initiates a media scan operation.
  *
  * It is possible to specify a configuration key prefix, from which the
  * settings for the archive are to be read. So the configuration format is
  * under control of the caller. If the configuration does not contain all
  * mandatory settings, archive creation will fail. (For this reason, the
  * creation method returns a ''Try'' object.)
  */
class HttpArchiveStarter {

  import HttpArchiveStarter._

  /**
    * Starts up the HTTP archive with the specified settings.
    *
    * @param unionArchiveActors an object with the actors for the union archive
    * @param config             the configuration
    * @param keyPrefix          the prefix key for configuration settings
    * @param credentials        the user credentials for reading data from the archive
    * @param actorFactory       the actor factory
    * @return a ''Try'' with a map of the actors created; keys are the names of
    *         the actor instances
    */
  def startup(unionArchiveActors: MediaFacadeActors, config: Configuration, keyPrefix: String,
              credentials: UserCredentials, actorFactory: ActorFactory):
  Try[Map[String, ActorRef]] = {
    val downloadConfig = DownloadConfig(config)
    val archiveConfig = HttpArchiveConfig(config, keyPrefix, credentials, downloadConfig)

    archiveConfig.map { ac =>
      createArchiveActors(unionArchiveActors, actorFactory, ac, config)
    }
  }

  /**
    * Creates the actors for the HTTP archive and ensures that anything is
    * initialized.
    *
    * @param unionArchiveActors the object with actors of the union archive
    * @param actorFactory       the actor factory
    * @param ac                 the archive configuration
    * @param config             the original configuration
    * @return the map with the actors created by this method
    */
  private def createArchiveActors(unionArchiveActors: MediaFacadeActors, actorFactory:
  ActorFactory, ac: HttpArchiveConfig, config: Configuration): Map[String, ActorRef] = {
    val pathGenerator = createPathGenerator(config)
    val removeActor = actorFactory.createActor(
      RemoveTempFilesActor(ClientApplication.BlockingDispatcherName), RemoveFileActorName)
    val monitoringActor = actorFactory.createActor(DownloadMonitoringActor(ac.downloadConfig),
      DownloadMonitoringActorName)
    val managerActor = actorFactory.createActor(HttpArchiveManagementActor(ac,
      pathGenerator, unionArchiveActors.mediaManager, unionArchiveActors.metaDataManager,
      monitoringActor, removeActor), ManagementActorName)

    managerActor ! ScanAllMedia
    removeActor ! RemoveTempFilesActor.ClearTempDirectory(pathGenerator.rootPath, pathGenerator)

    Map(ManagementActorName -> managerActor,
      DownloadMonitoringActorName -> monitoringActor,
      RemoveFileActorName -> removeActor)
  }
}