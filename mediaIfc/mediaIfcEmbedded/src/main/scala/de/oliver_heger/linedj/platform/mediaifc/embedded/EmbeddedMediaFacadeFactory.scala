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

package de.oliver_heger.linedj.platform.mediaifc.embedded

import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.actors.ActorBasedMediaFacadeFactory

/**
  * An implementation of
  * [[de.oliver_heger.linedj.platform.mediaifc.MediaFacadeFactory]] that creates
  * an [[EmbeddedMediaFacade]].
  */
class EmbeddedMediaFacadeFactory extends ActorBasedMediaFacadeFactory {
  override protected[embedded] def createFacadeImpl(relayActor: ActorRef, system: ActorSystem,
                                                    bus: MessageBus): MediaFacade =
    new EmbeddedMediaFacade(relayActor, system, bus)
}
