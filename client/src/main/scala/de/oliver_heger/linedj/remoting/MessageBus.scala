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

package de.oliver_heger.linedj.remoting

import akka.actor.Actor.Receive

/**
 * A trait defining a bus for publishing messages to registered listeners.
 *
 * This bus is used for both the internal communication in the UI of an
 * application and the communication to the remote server. The idea is that
 * multiple listeners can register at the bus and are then notified about
 * incoming messages. The bus is responsible for synchronization of messages
 * ensuring that message listeners are invoked in the UI thread.
 *
 * Message listeners are registered in terms of a partial function defining the
 * messages this listener is interested in. This is analogously to message
 * processing in actors. A listener gets invoked for all messages accepted by
 * its function. To be able to remove a listener again in a convenient way (the
 * listener might only be an anonymous function), a listener registration
 * returns an ID; this ID can then be used for removing the listener again.
 */
trait MessageBus {
  /**
   * Publishes a message on the message bus. All registered listeners that can
   * handle this message are invoked. Note that this is an asynchronous
   * operation; it returns directly while the invocation of listeners happens
   * in a background thread.
   * @param msg the message to be published
   */
  def publish(msg: Any): Unit

  /**
   * Registers a listener at this message bus. The listener will be invoked for
   * incoming messages that it can handle. The return value is a unique ID that
   * can be passed to ''removeListener()'' when the listener is no longer
   * needed.
   * @param r the listener to be registered
   * @return a unique ID for this listener
   */
  def registerListener(r: Receive): Int

  /**
   * Removes the listener with the specified ID from this message bus.
   * @param listenerID the ID of the listener to be removed
   */
  def removeListener(listenerID: Int): Unit
}
