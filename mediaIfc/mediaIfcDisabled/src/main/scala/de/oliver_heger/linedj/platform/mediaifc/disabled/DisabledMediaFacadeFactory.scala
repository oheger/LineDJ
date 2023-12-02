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

package de.oliver_heger.linedj.platform.mediaifc.disabled

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacadeFactory
import de.oliver_heger.linedj.utils.ActorFactory

/**
  * An implementation of [[MediaFacadeFactory]] that returns a
  * [[DisabledMediaFacade]].
  *
  * This is the corresponding factory implementation for this bundle. It
  * returns a facade implementation which does not interact with a real media
  * archive.
  */
class DisabledMediaFacadeFactory extends MediaFacadeFactory:
  /**
    * @inheritdoc This implementation creates a [[DisabledMediaFacade]] that is
    *             initialized with the passed in ''MessageBus''.
    */
  override def createMediaFacade(actorFactory: ActorFactory, bus: MessageBus): DisabledMediaFacade =
  new DisabledMediaFacade(bus)
