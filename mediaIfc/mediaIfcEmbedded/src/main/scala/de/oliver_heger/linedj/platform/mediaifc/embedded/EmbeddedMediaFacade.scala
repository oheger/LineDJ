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

package de.oliver_heger.linedj.platform.mediaifc.embedded

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.actors.ActorBasedMediaFacade
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.{ActorRef, ActorSystem}

/**
  * An implementation of ''MediaFacade'' that accesses a media archive running
  * in the same JVM.
  *
  * This class extends the base class for actor-based access to the media
  * archive by using a path prefix for looking up local actors. The lookup
  * paths to be used are fix and do not require any configuration.
  *
  * It may be a slight overhead to do dynamic lookups to local actors. However,
  * the concept of a relay actor is needed anyway to publish responses from the
  * media archive on the UI message bus. And, because the media archive is
  * implemented and started by a different bundle, a retried lookup may be
  * necessary to establish the connection to the target actors.
  *
  * @param relayActor the relay actor
  * @param system     the actor system
  * @param bus        the UI message bus
  */
class EmbeddedMediaFacade(relayActor: ActorRef, system: ActorSystem, bus: MessageBus)
  extends ActorBasedMediaFacade(relayActor, system, bus) {
  /**
    * @inheritdoc This implementation returns a prefix for a direct lookup of a
    *             local actor. The passed in configuration is not used.
    */
  override protected[embedded] def createActorPathPrefix(config: Configuration): String =
  "/user/"
}
