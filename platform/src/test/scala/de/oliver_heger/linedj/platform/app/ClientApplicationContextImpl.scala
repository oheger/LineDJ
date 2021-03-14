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

package de.oliver_heger.linedj.platform.app

import akka.actor.ActorSystem
import de.oliver_heger.linedj.platform.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import net.sf.jguiraffe.gui.builder.window.{WindowManager, WindowManagerImpl}
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatestplus.mockito.MockitoSugar

/**
  * An implementation of ''ClientApplicationContext'' which stores a bunch of
  * mock objects.
  *
  * @param configuration the configuration for the application
  * @param optMessageBus an optional message bus object
  */
class ClientApplicationContextImpl(configuration: Configuration = new PropertiesConfiguration(),
                                   optMessageBus: Option[MessageBus] = None)
  extends ClientApplicationContext with MockitoSugar {
  override val actorSystem: ActorSystem = mock[ActorSystem]

  override val messageBus: MessageBus = optMessageBus getOrElse mock[MessageBus]

  override val windowManager: WindowManager = new WindowManagerImpl

  override val actorFactory: ActorFactory = mock[ActorFactory]

  override val mediaFacade: MediaFacade = mock[MediaFacade]

  override val mediaIfcConfig: None.type = None

  override val managementConfiguration: Configuration = configuration
}
