/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.client.mediaifc.remote

import de.oliver_heger.linedj.client.comm.{ActorFactory, MessageBus}
import de.oliver_heger.linedj.client.mediaifc.MediaFacadeFactory
import de.oliver_heger.linedj.client.mediaifc.actors.ActorBasedMediaFacade
import de.oliver_heger.linedj.client.mediaifc.actors.impl.ManagementActor

object ActorBasedMediaFacadeFactory {
  /** The name of the remote management actor. */
  val ManagementActorName = "RemoteManagementActor"
}

/**
  * An implementation of [[MediaFacadeFactory]] that creates an instance of
  * [[ActorBasedMediaFacade]].
  *
  * The ''MediaFacade'' created by this factory communicates with the media
  * archive via an actor that tries to look up the media actors (implementing
  * the archive) dynamically.
  */
class ActorBasedMediaFacadeFactory extends MediaFacadeFactory {

  import ActorBasedMediaFacadeFactory._

  /**
    * @inheritdoc This implementation creates a ''ManagementActor'' and an
    *             [[ActorBasedMediaFacade]] that communicates with this actor.
    */
  override def createMediaFacade(actorFactory: ActorFactory, messageBus: MessageBus):
  ActorBasedMediaFacade = {
    val managementActor = actorFactory.createActor(ManagementActor(messageBus),
      ManagementActorName)
    //new ActorBasedMediaFacade(managementActor, actorFactory.actorSystem, messageBus)
    //TODO create correct facade instance
    null
  }
}
