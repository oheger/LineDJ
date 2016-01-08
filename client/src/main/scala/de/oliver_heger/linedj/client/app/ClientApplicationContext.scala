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

package de.oliver_heger.linedj.client.app

import akka.actor.ActorSystem
import de.oliver_heger.linedj.client.remoting.{ActorFactory, MessageBus, RemoteMessageBus}
import net.sf.jguiraffe.gui.platform.javafx.builder.window.StageFactory

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
    * Returns the remote message bus.
    * @return the remote message bus
    */
  def remoteMessageBus: RemoteMessageBus

  /**
    * Returns the (local) message bus.
    * @return the local message bus
    */
  def messageBus: MessageBus

  /**
    * Returns the factory for creating stages. All client applications use a
    * shared stage factory. It can be queried using this property.
    * @return the ''StageFactory''
    */
  def stageFactory: StageFactory
}
