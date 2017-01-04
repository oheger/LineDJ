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

package de.oliver_heger.linedj.archivestart

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.platform.app.ClientApplicationContext
import de.oliver_heger.linedj.platform.mediaifc.MediaActors
import de.oliver_heger.linedj.shared.archive.media.ScanAllMedia
import org.apache.commons.configuration.HierarchicalConfiguration
import org.osgi.service.component.ComponentContext
import org.slf4j.LoggerFactory

/**
  * A class that starts the media archive in an OSGi environment.
  *
  * This class is a declarative services component that has a dependency on the
  * ''ClientApplicationContext'' service. It is started when this service
  * becomes available. From the context, the local actor system and the
  * configuration of the management application can be obtained.
  *
  * On activation, this component creates an ''MediaArchiveConfig'' object from
  * the configuration of the management application. Then it creates the actors
  * implementing the functionality of the media archive and all their
  * dependencies. Afterwards, the media archive can be accessed via the
  * current implementation of the ''MediaFacade'' trait.
  */
class MediaArchiveStartup {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** The client application context. */
  private var clientApplicationContext: ClientApplicationContext = _

  /**
    * Initializes the reference to the ''ClientApplicationContext''. This
    * method is called by the SCR.
    *
    * @param clientContext the ''ClientApplicationContext''
    */
  def initClientApplicationContext(clientContext: ClientApplicationContext): Unit = {
    clientApplicationContext = clientContext
  }

  /**
    * Activates this component. This method is called by the SCR. Here the
    * actors implementing the media archive are created.
    *
    * @param compCtx the ''ComponentContext''
    */
  def activate(compCtx: ComponentContext): Unit = {
    log.info("Starting up media archive.")
    val archiveConfig = MediaArchiveConfig(clientApplicationContext.managementConfiguration
      .asInstanceOf[HierarchicalConfiguration])
    val persistentMetaDataManager = createActor(PersistentMetaDataManagerActor(archiveConfig),
      "persistentMetaDataManager")
    val metaDataManager = createActor(MetaDataManagerActor(archiveConfig,
      persistentMetaDataManager), MediaActors.MetaDataManager.name)
    val mediaManager = createActor(MediaManagerActor(archiveConfig, metaDataManager),
      MediaActors.MediaManager.name)
    mediaManager ! ScanAllMedia
  }

  /**
    * Helper method for creating a new actor.
    *
    * @param props creation properties
    * @param name  the actor name
    * @return the new actor reference
    */
  private def createActor(props: Props, name: String): ActorRef =
  clientApplicationContext.actorFactory.createActor(props, name)
}
