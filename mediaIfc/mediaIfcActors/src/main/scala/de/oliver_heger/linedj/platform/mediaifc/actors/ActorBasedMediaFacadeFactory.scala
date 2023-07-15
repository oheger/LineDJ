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

package de.oliver_heger.linedj.platform.mediaifc.actors

import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.actors.impl.ManagementActor
import de.oliver_heger.linedj.platform.mediaifc.{MediaFacade, MediaFacadeFactory}
import de.oliver_heger.linedj.utils.ActorFactory

object ActorBasedMediaFacadeFactory {
  /** The name of the management actor used by the facade. */
  val ManagementActorName = "FacadeManagementActor"
}

/**
  * A base trait for a [[MediaFacadeFactory]] that creates a facade based on
  * [[ActorBasedMediaFacade]].
  *
  * This trait already implements the logic for the creation of the relay
  * actor. A concrete subclass has to create the actual facade.
  */
trait ActorBasedMediaFacadeFactory extends MediaFacadeFactory {

  import ActorBasedMediaFacadeFactory._

  /**
    * @inheritdoc This implementation uses the ''ActorFactory'' to create the
    *             management actor. It then delegates to ''createFacadeImpl()''
    *             to create the actual facade instance.
    */
  override def createMediaFacade(actorFactory: ActorFactory, bus: MessageBus): MediaFacade = {
    val relayActor = actorFactory.createActor(ManagementActor(bus), ManagementActorName)
    createFacadeImpl(relayActor, actorFactory.actorSystem, bus)
  }

  /**
    * Creates the actual facade instance with the passed in parameters.
    *
    * @param relayActor the relay actor to be used by the facade
    * @param system     the actor system
    * @param bus        the UI message bus
    * @return the new ''MediaFacade'' instance
    */
  protected def createFacadeImpl(relayActor: ActorRef, system: ActorSystem,
                                 bus: MessageBus): MediaFacade
}
