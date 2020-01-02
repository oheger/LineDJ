/*
 * Copyright 2015-2019 The Developers Team.
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
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.group.ArchiveGroupActor
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.MediaFacadeActors
import org.osgi.service.component.ComponentContext
import org.slf4j.LoggerFactory

object LocalArchiveStartup {
  /** Name for the archive group actor. */
  val NameGroupActor = "archiveGroupActor"
}

/**
  * A class that starts a local media archive in an OSGi environment.
  *
  * This class is a declarative services component. Like other
  * [[PlatformComponent]] implementations, it depends on the ''client
  * application context''. In addition, it gets an object injected with the
  * actors that implement the media facade interface. With these actors the
  * local archive can be started directly. Information about the paths
  * to be scanned must be provided in the configuration of the management
  * application.
  */
class LocalArchiveStartup extends PlatformComponent with ClientContextSupport
  with ActorManagement {

  import LocalArchiveStartup._

  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** The object holding the actors of the media facade. */
  private var facadeActors: MediaFacadeActors = _

  /**
    * Initializes the actors for the media facade interface. This method is
    * called by the declarative services runtime.
    *
    * @param mfa the media facade actors
    */
  def initMediaFacadeActors(mfa: MediaFacadeActors): Unit = {
    facadeActors = mfa
  }

  /**
    * @inheritdoc This implementation creates the registration for the
    *             archive available extension to monitor the archive state.
    */
  override def activate(compContext: ComponentContext): Unit = {
    super.activate(compContext)
    log.info("Activating LocalArchiveStartup.")
    startLocalArchive(facadeActors.mediaManager, facadeActors.metaDataManager)
  }

  /**
    * Creates and initializes the actors for the local media archive. A new
    * scan is started immediately.
    *
    * @param mediaUnionActor    the union media actor
    * @param metaDataUnionActor the union meta data actor
    */
  private def startLocalArchive(mediaUnionActor: ActorRef, metaDataUnionActor: ActorRef): Unit = {
    val archiveConfigs = MediaArchiveConfig(clientApplicationContext.managementConfiguration)
    createAndRegisterActor(ArchiveGroupActor(mediaUnionActor, metaDataUnionActor, archiveConfigs), NameGroupActor)
    log.info("Local archive started.")
  }
}
