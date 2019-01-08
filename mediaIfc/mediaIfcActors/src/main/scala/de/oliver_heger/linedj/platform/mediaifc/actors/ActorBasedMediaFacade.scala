/*
 * Copyright 2015-2019 The Developers Team.
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
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.actors.impl.{ManagementActor, RelayActor}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import org.apache.commons.configuration.Configuration

import scala.concurrent.Future

/**
  * A base implementation of [[de.oliver_heger.linedj.platform.mediaifc.MediaFacade]]
  * that communicates with the media archive via a relay actor.
  *
  * This implementation is passed an actor at construction time which is
  * responsible for looking up the actors of the media archive and delegating
  * messages to them. Responses for such messages are published on the UI
  * message bus.
  *
  * This base class already implements the major part of the functionality
  * required for a media archive interface. A concrete subclass has to provide
  * a template for the path of the actors to be looked up. That way the class
  * can deal with different deployment scenarios transparently, e.g. the media
  * archive running on another machine or embedded into the current process.
  *
  * @param relayActor  the ''RelayActor''
  * @param actorSystem the associated actor system
  * @param bus         the underlying message bus
  */
abstract class ActorBasedMediaFacade(val relayActor: ActorRef, val actorSystem: ActorSystem,
                                     override val bus: MessageBus)
  extends MediaFacade {

  /**
   * Sends an ''Activate'' message to the relay actor. This is a
   * convenient way to enable or disable monitoring of the server state.
   * @param enabled the enabled flag
   */
  override def activate(enabled: Boolean): Unit = {
    relayActor ! RelayActor.Activate(enabled)
  }

  /**
   * Sends a message to a remote actor. No answer is expected; so the message
   * is just passed to the specified target remote actor.
   * @param target the target actor
   * @param msg the message
   */
  override def send(target: MediaActor, msg: Any): Unit = {
    relayActor ! RelayActor.MediaMessage(target, msg)
  }

  /**
    * @inheritdoc This implementation sends a corresponding message to the
    *             relay actor.
    */
  override def requestMediaState(): Unit = {
    relayActor ! RelayActor.QueryServerState
  }

  /**
    * @inheritdoc This implementation delegates to the relay actor.
    */
  override def requestActor(target: MediaActor)(implicit timeout: Timeout):
  Future[Option[ActorRef]] = {
    implicit val ec = actorSystem.dispatcher
    val future = relayActor ? RelayActor.MediaActorRequest(target)
    future.map(f => f.asInstanceOf[RelayActor.MediaActorResponse].optActor)
  }

  /**
    * @inheritdoc This implementation sends a special message to the relay
    *             actor. The relay actor then handles the removal of the
    *             listener.
    */
  override def removeMetaDataListener(mediumID: MediumID): Unit = {
    relayActor ! RelayActor.RemoveListener(mediumID)
  }

  /**
    * @inheritdoc This implementation delegates to ''createActorPathPrefix()''
    *             to generate the prefix for lookup operations. The resulting
    *             string is then passed to the management actor.
    */
  override def initConfiguration(config: Configuration): Unit = {
    relayActor ! ManagementActor.ActorPathPrefix(createActorPathPrefix(config))
  }

  /**
    * @inheritdoc This implementation sends a corresponding registration
    *             message to the relay actor.
    */
  override def registerMetaDataStateListener(componentID: ComponentID): Unit = {
    relayActor ! RelayActor.RegisterStateListener(componentID)
  }

  /**
    * @inheritdoc This implementation sends a corresponding message to remove a
    *             state listener registration to the relay actor.
    */
  override def unregisterMetaDataStateListener(componentID: ComponentID): Unit = {
    relayActor ! RelayActor.UnregisterStateListener(componentID)
  }

  /**
    * Generates the actor lookup prefix based on the passed in configuration.
    * Here the string is generated which is used by the interface actors to
    * lookup the actors of the media archive.
    *
    * @param config the configuration object
    * @return the path prefix for actor lookup operations
    */
  protected def createActorPathPrefix(config: Configuration): String
}
