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

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef}
import de.oliver_heger.linedj.client.comm.MessageBus
import de.oliver_heger.linedj.client.mediaifc.MediaActors.MediaActor

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
  * @param relayActor the ''RemoteRelayActor''
  * @param bus        the underlying message bus
  */
class ActorBasedMediaFacade(val relayActor: ActorRef, val bus: MessageBus) {
  /**
   * Sends an ''Activate'' message to the relay actor. This is a
   * convenient way to enable or disable monitoring of the server state.
   * @param enabled the enabled flag
   */
  def activate(enabled: Boolean): Unit = {
    relayActor ! RelayActor.Activate(enabled)
  }

  /**
   * Sends a message to a remote actor. No answer is expected; so the message
   * is just passed to the specified target remote actor.
   * @param target the target actor
   * @param msg the message
   */
  def send(target: MediaActor, msg: Any): Unit = {
    relayActor ! RelayActor.RemoteMessage(target, msg)
  }

  /**
   * Sends a message to a remote actor and registers a listener at the
   * underlying message bus to handle the response. The response listener stays
   * connected until a message is received which it can handle.
   * @param target the target actor
   * @param msg the message
   * @param responseListener the handler for the response
   */
  def ask(target: MediaActor, msg: Any)(responseListener: Actor.Receive): Unit = {
    val refListenerID = new AtomicInteger
    val listenerID = bus registerListener wrapReceive(responseListener, refListenerID)
    refListenerID set listenerID
    send(target, msg)
  }

  /**
    * Sends a message to the remote actor indicating a configuration change.
    * This will cause a new relay actor to be created using the new remote
    * address.
    * @param address the remote address
    * @param port the remote port
    */
  def updateConfiguration(address: String, port: Int): Unit = {
    relayActor ! ManagementActor.RemoteConfiguration(address, port)
  }

  /**
    * Sends a message which queries the current server state to the relay
    * actor. As reaction the server state is published on the message bus.
    */
  def queryServerState(): Unit = {
    relayActor ! RelayActor.QueryServerState
  }

  /**
   * Wraps a receive function to ensure that it is removed from the message bus
   * when a response message is received.
   * @param r the function to be wrapped
   * @param refRegistrationID a reference to the ID of the listener registration
   * @return the wrapper function
   */
  private def wrapReceive(r: Actor.Receive, refRegistrationID: AtomicInteger): Actor.Receive = {
    new PartialFunction[Any, Unit] {
      override def isDefinedAt(x: Any): Boolean = r isDefinedAt x

      override def apply(msg: Any): Unit = {
        r(msg)
        bus removeListener refRegistrationID.get()
      }
    }
  }
}
