/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.client.app

import de.oliver_heger.linedj.client.remoting.{RemoteManagementActor, RemoteMessageBus,
MessageBus, ActorFactory}

object RemoteMessageBusFactory {
  /** The name of the remote management actor. */
  val ManagementActorName = "RemoteManagementActor"
}

/**
  * An internally used helper class for creating an instance of a remote
  * message bus.
  *
  * A remote message bus is a more complex bean which cannot be created simply
  * using the dependency injection framework. It involves creating an actor.
  * Therefore, this class was introduced to encapsulate the creation
  * functionality.
  */
private class RemoteMessageBusFactory {

  import RemoteMessageBusFactory._

  /**
    * Creates a new ''RemoteMessageBus'' object.
    * @param actorFactory the factory for creating actors
    * @param messageBus the message bus
    * @return the newly created remote message bus
    */
  def createRemoteMessageBus(actorFactory: ActorFactory, messageBus: MessageBus):
  RemoteMessageBus = {
    val managementActor = actorFactory.createActor(RemoteManagementActor(messageBus),
      ManagementActorName)
    new RemoteMessageBus(managementActor, messageBus)
  }
}
