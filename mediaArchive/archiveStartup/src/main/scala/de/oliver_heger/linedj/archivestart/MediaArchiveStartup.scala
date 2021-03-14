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

package de.oliver_heger.linedj.archivestart

import akka.actor.Props
import de.oliver_heger.linedj.archiveunion.{MediaArchiveConfig, MediaUnionActor, MetaDataUnionActor}
import de.oliver_heger.linedj.platform.app.{ClientContextSupport, PlatformComponent}
import de.oliver_heger.linedj.platform.app.support.ActorManagement
import de.oliver_heger.linedj.platform.mediaifc.MediaActors
import org.osgi.service.component.ComponentContext
import org.slf4j.LoggerFactory

/**
  * A class that starts the (union) media archive in an OSGi environment.
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
class MediaArchiveStartup extends PlatformComponent with ClientContextSupport
  with ActorManagement {
  /** The logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /**
    * Activates this component. This method is called by the SCR. Here the
    * actors implementing the media archive are created.
    *
    * @param compCtx the ''ComponentContext''
    */
  override def activate(compCtx: ComponentContext): Unit = {
    super.activate(compCtx)
    log.info("Starting up media archive.")
    val archiveConfig = MediaArchiveConfig(clientApplicationContext.managementConfiguration)
    val metaDataManager = createAndRegisterActor(Props(classOf[MetaDataUnionActor],
      archiveConfig), MediaActors.MetaDataManager.name)
    val mediaManager = createAndRegisterActor(MediaUnionActor(metaDataManager),
      MediaActors.MediaManager.name)
  }
}
