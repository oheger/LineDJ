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

package de.oliver_heger.linedj.platform.app

import akka.actor.ActorSystem
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import net.sf.jguiraffe.gui.builder.window.WindowManager
import org.apache.commons.configuration.Configuration

/**
  * A trait defining central context functionality for LineDJ client
  * applications.
  *
  * Each LineDJ client application has access to some central objects which
  * enable the communication with the server and with other client
  * applications. These objects can be queried via this trait.
  *
  * In addition, there are methods registering applications and terminating
  * the whole client. This functionality makes it possible to run multiple
  * independent applications in a single container.
  */
trait ClientApplicationContext {
  /**
    * Returns the actor system running on the client.
    * @return the central actor system
    */
  def actorSystem: ActorSystem

  /**
    * Returns the factory for creating new actors.
    * @return the actor factory
    */
  def actorFactory: ActorFactory

  /**
    * Returns the facade for accessing the media archive.
    * @return the ''MediaFacade''
    */
  def mediaFacade: MediaFacade

  /**
    * Returns the (local) message bus.
    * @return the local message bus
    */
  def messageBus: MessageBus

  /**
    * Returns the shared window manager to be used by all client applications.
    * As the window manager is related to the correct initialization of the UI
    * platform (especially in case of JavaFX), it is important that all client
    * applications running in a deployment use the same instance.
    * @return the shared window manager
    */
  def windowManager: WindowManager

  /**
    * Returns the configuration of the management application. This
    * configuration can be used by other modules to store some settings which
    * are not really application-specific, but more central.
    * @return the configuration of the management application
    */
  def managementConfiguration: Configuration

  /**
    * Returns an option with configuration data about the media archive
    * interface. An object implementing ''MediaIfcConfigData'' can be
    * registered in the OSGi registry and queried via this method. If an object
    * exists, an application needing access to the media archive should offer
    * an option to the user to display the configuration dialog.
    * @return an option with configuration data for the media archive
    */
  def mediaIfcConfig: Option[MediaIfcConfigData]
}
