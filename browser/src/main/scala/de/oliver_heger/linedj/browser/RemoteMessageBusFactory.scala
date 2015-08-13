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

package de.oliver_heger.linedj.browser

import akka.actor.{ActorRef, ActorSystem}
import de.oliver_heger.linedj.remoting.{MessageBus, RemoteMessageBus, RemoteRelayActor}
import net.sf.jguiraffe.gui.app.ApplicationContext

object RemoteMessageBusFactory {
  /** Configuration property for the server address. */
  val PropServerAddress = "remote.server.address"

  /** Configuration property for the server port. */
  val PropServerPort = "remote.server.port"

  /** The name of the remote relay actor. */
  val RelayActorName = "RemoteRelayActor"

  /** Constant for the default server address. */
  val DefaultServerAddress = "127.0.0.1"

  /** Constant for the default server port. */
  val DefaultServerPort = 2552
}

/**
 * An internally used helper class for creating an instance of a remote
 * message bus.
 *
 * A remote message bus is a more complex bean which cannot be created simply
 * using the dependency injection framework. It involves creating an actor.
 * Therefore, this class was introduced to encapsulate the creation
 * functionality. Note that this is used again when the connection to the
 * server is changed by the user.
 */
private class RemoteMessageBusFactory {

  import RemoteMessageBusFactory._

  /**
   * Creates a new message bus object using beans defined in the given
   * application context. If already an old instance exists, it is discarded.
   * This method can be called when a connection to the server is changed; the
   * current server address and port must be stored in the current
   * configuration object.
   * @param context the application context
   * @return the newly created ''RemoteMessageBus''
   */
  def recreateRemoteMessageBus(context: ApplicationContext): RemoteMessageBus = {
    val actorSystem = context.getBeanContext.getBean(BrowserApp.BeanActorSystem)
      .asInstanceOf[ActorSystem]
    discardOldRemoteMessageBus(context, actorSystem)
    val messageBus = context.getBeanContext.getBean(BrowserApp.BeanMessageBus)
      .asInstanceOf[MessageBus]
    val relayActor = createRelayActor(context, actorSystem, messageBus)
    new RemoteMessageBus(relayActor, messageBus)
  }

  /**
   * Discards an already existing ''RemoteMessageBus'' if a corresponding bean
   * is found in the context. The relay actor of this message bus has to be
   * stopped.
   * @param context the application context
   * @param actorSystem the actor system
   */
  private def discardOldRemoteMessageBus(context: ApplicationContext, actorSystem: ActorSystem):
  Unit = {
    if (context.getBeanContext.containsBean(BrowserApp.BeanRemoteMessageBus)) {
      val oldRemoteBus = context.getBeanContext.getBean(BrowserApp.BeanRemoteMessageBus)
        .asInstanceOf[RemoteMessageBus]
      actorSystem stop oldRemoteBus.relayActor
    }
  }

  /**
   * Creates the remote relay actor.
   * @param context the application context
   * @param actorSystem the actor system
   * @param messageBus the message bus
   * @return the actor reference
   */
  private def createRelayActor(context: ApplicationContext, actorSystem: ActorSystem, messageBus:
  MessageBus): ActorRef = {
    val address = context.getConfiguration.getString(PropServerAddress, DefaultServerAddress)
    val port = context.getConfiguration.getInt(PropServerPort, DefaultServerPort)
    actorSystem.actorOf(RemoteRelayActor(address, port, messageBus),
      RelayActorName)
  }
}
