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

import akka.actor.{Actor, ActorRef, Props}
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.remote.ManagementActor.RemoteConfiguration
import de.oliver_heger.linedj.utils.ChildActorFactory

object ManagementActor {

  /**
    * A message processed by [[ManagementActor]] which defines the
    * configuration of the remote system. When a message of this type is
    * received, a new relay actor is created which is configured with these
    * settings.
    *
    * @param address the address of the remote actor system
    * @param port the port of the remote actor system
    */
  case class RemoteConfiguration(address: String, port: Int)

  private class ManagementActorImpl(bus: MessageBus) extends ManagementActor(bus)
  with ChildActorFactory

  /**
    * Creates a ''Props'' object for creating an instance of this actor.
    * @param messageBus the message bus
    * @return the ''Props'' for creating a new instance
    */
  def apply(messageBus: MessageBus): Props = Props(classOf[ManagementActorImpl], messageBus)
}

/**
  * An actor which manages the communication with the remote actor system.
  *
  * This actor is basically a wrapper around [[RelayActor]] which deals
  * with the configuration of the remote connection. The user can change the
  * address or port of the remote actor system at any time (it may be incorrect
  * at the beginning). When this happens the ''RelayActor'' actor has to
  * be restarted with the updated configuration settings. However, this should
  * be transparent for other parts of the system; it has to be avoided that the
  * actor reference has to be updated. This actor solves this problem. It
  * routes all messages through to the ''RelayActor'' which is a child of
  * this actor. Only messages indicating a configuration change are processed.
  *
  * Before the remote connection can be set up, an initial configuration change
  * message must be received. So, the first step when using this actor is
  * typically sending it a message with the current configuration data
  * regarding remote access.
  *
  * @param messageBus the message bus
  */
class ManagementActor(messageBus: MessageBus) extends Actor {
  this: ChildActorFactory =>

  /** The current relay child actor. */
  private var relayActor: ActorRef = _

  override def receive: Receive = uninitialized

  /**
    * The initial receive function; no configuration has been set so far.
    */
  private def uninitialized: Receive = {
    case RemoteConfiguration(address, port) =>
      updateRelayActorForNewConfiguration(address, port)
      context become configured
  }

  /**
    * The receive function after the initial configuration has been set.
    */
  private def configured: Receive = {
    case RemoteConfiguration(address, port) =>
      context stop relayActor
      updateRelayActorForNewConfiguration(address, port)
      relayActor ! RelayActor.Activate(true)

    case msg =>
      relayActor forward msg
  }

  /**
    * Creates a new releay actor which uses the passed in configuration
    * settings. This is called when the configuration was updated.
    * @param address the remote address
    * @param port the remote port
    */
  private def updateRelayActorForNewConfiguration(address: String, port: Int): Unit = {
    relayActor = createChildActor(RelayActor(address, port, messageBus))
  }
}
