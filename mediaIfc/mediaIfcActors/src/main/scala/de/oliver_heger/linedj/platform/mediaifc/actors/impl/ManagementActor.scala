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

package de.oliver_heger.linedj.platform.mediaifc.actors.impl

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.actors.impl.ManagementActor.ActorPathPrefix
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.{Actor, ActorRef, Props}

object ManagementActor:

  /**
    * A message processed by [[ManagementActor]] which defines the path of the
    * actors to be looked up. The actor-based interface to the media archive
    * has to lookup the actors implementing the archive's functionality. The
    * path for this lookup operation depends on the concrete deployment
    * scenario (local or remote). With this message the prefix of the lookup
    * path is set; here the actor name is appended.
    *
    * @param prefix the prefix for an actor lookup path
    */
  case class ActorPathPrefix(prefix: String)

  private class ManagementActorImpl(bus: MessageBus) extends ManagementActor(bus)
  with ChildActorFactory

  /**
    * Creates a ''Props'' object for creating an instance of this actor.
    * @param messageBus the message bus
    * @return the ''Props'' for creating a new instance
    */
  def apply(messageBus: MessageBus): Props = Props(classOf[ManagementActorImpl], messageBus)

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
class ManagementActor(messageBus: MessageBus) extends Actor:
  this: ChildActorFactory =>

  /** The current relay child actor. */
  private var relayActor: ActorRef = _

  override def receive: Receive = uninitialized

  /**
    * The initial receive function; no configuration has been set so far.
    */
  private def uninitialized: Receive =
    case ActorPathPrefix(prefix) =>
      updateRelayActorForNewConfiguration(prefix)
      context become configured

  /**
    * The receive function after the initial configuration has been set.
    */
  private def configured: Receive =
    case ActorPathPrefix(prefix) =>
      context stop relayActor
      updateRelayActorForNewConfiguration(prefix)
      relayActor ! RelayActor.Activate(true)

    case msg =>
      relayActor forward msg

  /**
    * Creates a new relay actor which uses the passed in path prefix
    * settings. This is called when the configuration was updated.
    * @param prefix the new actor path lookup prefix
    */
  private def updateRelayActorForNewConfiguration(prefix: String): Unit =
    relayActor = createChildActor(RelayActor(prefix, messageBus))
