/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.config.MediaIfcConfigData
import de.oliver_heger.linedj.utils.ActorFactory
import net.sf.jguiraffe.gui.builder.window.{WindowManager, WindowManagerImpl}
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.apache.pekko.actor.ActorSystem
import org.scalatestplus.mockito.MockitoSugar.mock

/**
  * An implementation of ''ClientApplicationContext'' which stores a bunch of
  * mock objects per default. It is possible to override some of the mocks when
  * creating an instance.
  */
class ClientApplicationContextImpl(override val managementConfiguration: Configuration = new PropertiesConfiguration(),
                                   override val messageBus: MessageBus = mock[MessageBus],
                                   override val actorSystem: ActorSystem = mock[ActorSystem],
                                   override val actorFactory: ActorFactory = mock[ActorFactory],
                                   override val mediaFacade: MediaFacade = mock[MediaFacade],
                                   override val windowManager: WindowManager = new WindowManagerImpl,
                                   override val mediaIfcConfig: Option[MediaIfcConfigData] = None)
  extends ClientApplicationContext
