/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.remote

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.actors.ActorBasedMediaFacade
import org.apache.commons.configuration.Configuration
import org.apache.pekko.actor.{ActorRef, ActorSystem}

/**
  * An implementation of [[ActorBasedMediaFacade]] for remote access to a
  * media archive server.
  *
  * This implementation reads the server address and port from the passed in
  * configuration and generates corresponding lookup path prefixes.
  */
class RemoteMediaFacade(relayActor: ActorRef, system: ActorSystem, bus: MessageBus)
  extends ActorBasedMediaFacade(relayActor, system, bus):
  /**
    * @inheritdoc This implementation reads configuration settings for the
    *             media archive server and generates a corresponding lookup
    *             path prefix. Default settings are applied for missing
    *             configuration options.
    */
  override protected[remote] def createActorPathPrefix(config: Configuration): String =
    s"pekko://${readActorSystemName(config)}@${readHost(config)}:${readPort(config)}/user/"
