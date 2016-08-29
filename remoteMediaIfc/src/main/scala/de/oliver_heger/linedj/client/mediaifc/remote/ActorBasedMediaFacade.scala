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

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.MediaActors.MediaActor
import de.oliver_heger.linedj.client.mediaifc.MediaFacade
import de.oliver_heger.linedj.media.MediumID
import org.apache.commons.configuration.Configuration

import scala.concurrent.Future

object ActorBasedMediaFacade {
  /**
    * Configuration property for the host of the remote media archive.
    */
  private val PropMediaArchiveHost = "media.host"

  /**
    * Configuration property for the port of the remote media archive.
    */
  private val PropMediaArchivePort = "media.port"

  /** Constant for the default media archive address. */
  val DefaultServerAddress = "127.0.0.1"

  /** Constant for the default media archive port. */
  val DefaultServerPort = 2552
}

/**
  * An implementation of [[de.oliver_heger.linedj.client.mediaifc.MediaFacade]]
  * that communicates with the media archive via a relay actor.
  *
  * This implementation can be used if the media archive runs in another JVM.
  * References to the actors wrapping the functionality of the media archive
  * are created and monitored by a helper actor class. Messages to the media
  * archive are passed to this actor; the responses are published on the UI
  * message bus.
  *
  * @param relayActor  the ''RelayActor''
  * @param actorSystem the associated actor system
  * @param bus         the underlying message bus
  */
class ActorBasedMediaFacade(val relayActor: ActorRef, val actorSystem: ActorSystem,
                            override val bus: MessageBus)
  extends MediaFacade {
  import ActorBasedMediaFacade._

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
    * @inheritdoc This implementation extracts properties relevant for access
    *             to a remote media archive from the given configuration.
    */
  override def initConfiguration(config: Configuration): Unit = {
    relayActor ! ManagementActor.RemoteConfiguration(config.getString(PropMediaArchiveHost,
      DefaultServerAddress), config.getInt(PropMediaArchivePort, DefaultServerPort))
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
}
