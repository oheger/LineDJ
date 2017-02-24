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

package de.oliver_heger.linedj.archivelocalstart

import akka.actor.ActorRef
import akka.util.Timeout
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.platform.app.{ClientApplicationContext, ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.bus.Identifiable
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.HierarchicalConfiguration
import org.osgi.service.component.ComponentContext
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._

object LocalArchiveStartup {
  /** Name for the persistence manager actor. */
  val NamePersistenceManager = "persistentMetaDataManager"

  /** Name for the local meta data manager actor. */
  val NameMetaDataManager = "localMetaDataManager"

  /** Name for the local media manager actor. */
  val NameMediaManager = "localMediaManager"

  /** Property for the initialization timeout of the local archive. */
  val PropArchiveInitTimeout = "media.initTimeout"

  /** The default initialization timeout (in seconds). */
  private val DefaultInitTimeout = 10

  /**
    * Determines the initialization timeout for the local media archive. This
    * value can be read from the configuration; if unspecified, a default value
    * is used.
    *
    * @param ctx the ''ClientApplicationContext''
    * @return the initialization timeout
    */
  private def fetchInitTimeout(ctx: ClientApplicationContext): Timeout =
    Timeout(ctx.managementConfiguration.getInt(PropArchiveInitTimeout,
      DefaultInitTimeout).seconds)
}

/**
  * A class that starts a local media archive in an OSGi environment.
  *
  * This class is started as a ''PlatformComponent''. It registers itself at
  * the media archive available extension and monitors the state of the
  * (global) archive. When it becomes available actors for a local media
  * archive are created, and a scan operation is triggered. This causes the
  * meta data fetched locally to be distributed to the global media archive.
  *
  * The communication with the media archive is done through the facade
  * interface. From there references to the union manager actors are queried.
  * Only if this is successful and valid actor references can be obtained,
  * the local archive is created and initialized. Information about the paths
  * to be scanned must be provided in the configuration of the management
  * application.
  */
class LocalArchiveStartup extends PlatformComponent with ClientContextSupport
  with ActorManagement with Identifiable {

  import LocalArchiveStartup._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** A flag whether the archive has been started. */
  private var archiveStarted = false

  /**
    * @inheritdoc This implementation creates the registration for the
    *             archive available extension to monitor the archive state.
    */
  override def activate(compContext: ComponentContext): Unit = {
    log.info("Activating LocalArchiveStartup.")
    clientApplicationContext.messageBus publish ArchiveAvailabilityRegistration(componentID,
      archiveAvailabilityChanged)
  }

  /**
    * @inheritdoc This implementation performs cleanup.
    */
  override def deactivate(componentContext: ComponentContext): Unit = {
    clientApplicationContext.messageBus publish ArchiveAvailabilityUnregistration(componentID)
    super.deactivate(componentContext)
    log.info("Deactivated LocalArchiveStartup.")
  }

  /**
    * The consumer function for the archive availability extension. Here
    * messages about the availability of the union media archive are processed.
    *
    * @param event the event
    */
  private def archiveAvailabilityChanged(event: MediaFacade.MediaArchiveAvailabilityEvent): Unit =
    event match {
      case MediaFacade.MediaArchiveAvailable =>
        handleArchiveAvailable()
      case MediaFacade.MediaArchiveUnavailable =>
        handleArchiveUnavailable()
    }

  /**
    * Handles the notification that the union archive is available. If not
    * yet done, the local archive is now started.
    */
  private def handleArchiveAvailable(): Unit = {
    if (!archiveStarted) {
      implicit val executionContext = clientApplicationContext.actorSystem.dispatcher
      implicit val timeout = fetchInitTimeout(clientApplicationContext)

      def requestArchiveActor(t: MediaActor): Future[ActorRef] =
        clientApplicationContext.mediaFacade.requestActor(t) map (_.get)

      log.info("Archive available. Starting up local archive.")
      val futMedia = requestArchiveActor(MediaActors.MediaManager)
      val futMeta = requestArchiveActor(MediaActors.MetaDataManager)
      Future.sequence(List(futMedia, futMeta)).foreach { actors =>
        startLocalArchive(actors.head, actors(1))
      }
    }
  }

  /**
    * Handles the notification that the union archive is no longer available.
    * This also causes the local archive to be stopped.
    */
  private def handleArchiveUnavailable(): Unit = {
    log.info("Archive unavailable. Stopping local archive.")
    stopActors()
    archiveStarted = false
  }

  /**
    * Creates and initializes the actors for the local media archive. A new
    * scan is started immediately.
    *
    * @param mediaUnionActor    the union media actor
    * @param metaDataUnionActor the union meta data actor
    */
  private def startLocalArchive(mediaUnionActor: ActorRef, metaDataUnionActor: ActorRef): Unit = {
    val archiveConfig = MediaArchiveConfig(clientApplicationContext.managementConfiguration
      .asInstanceOf[HierarchicalConfiguration])
    val persistentMetaDataManager = createAndRegisterActor(
      PersistentMetaDataManagerActor(archiveConfig), NamePersistenceManager)
    val metaDataManager = createAndRegisterActor(MetaDataManagerActor(archiveConfig,
      persistentMetaDataManager, metaDataUnionActor), NameMetaDataManager)
    val mediaManager = createAndRegisterActor(MediaManagerActor(archiveConfig,
      metaDataManager, mediaUnionActor), NameMediaManager)
    mediaManager ! ScanAllMedia
    archiveStarted = true
  }
}
