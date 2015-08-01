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

package de.oliver_heger.linedj.bus

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.remoting.MessageBus
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer

/**
 * An implementation of the [[de.oliver_heger.linedj.remoting.MessageBus]]
 * trait for a UI application.
 *
 * This implementation is mainly based on a ''GUISynchronizer'' in order to
 * implement messaging in a way appropriate for UI communication. All
 * registration and invocation of listeners is done in the UI thread of the
 * underlying UI framework. This makes it possible for bus listeners to
 * directly interact with UI components in a safe way.
 *
 * @param sync the object handling the synchronization with the UI thread
 */
class UIBus(sync: GUISynchronizer) extends MessageBus {
  /** The list of currently registered listeners. */
  private var listeners = List.empty[Receive]

  /**
   * @inheritdoc This implementation iterates over all listeners in the UI
   *             thread. All listeners that can handle the message are
   *             invoked.
   */
  override def publish(msg: Any): Unit = {
    runAsync {
      listeners foreach { l =>
        if (l isDefinedAt msg) {
          l(msg)
        }
      }
    }
  }

  /**
   * @inheritdoc This implementation adds the listener to an internal list;
   *             this happens asynchronously in the UI thread. The listener ID
   *             is calculated from the listeners hash code.
   */
  override def registerListener(r: Receive): Int = {
    runAsync {
      listeners = r :: listeners
    }
    r.hashCode()
  }

  /**
   * @inheritdoc This implementation removes all listeners with the given ID
   *             from the internal list. This happens asynchronously in the UI
   *             thread. The listener ID is again calculated from the listeners
   *             hash code.
   */
  override def removeListener(listenerID: Int): Unit = {
    runAsync {
      listeners = listeners filterNot (_.hashCode() == listenerID)
    }
  }

  /**
   * Helper method for running code asynchronously on the UI thread.
   * @param r the code to be run
   */
  private def runAsync(r: => Unit): Unit = {
    sync asyncInvoke new Runnable {
      override def run(): Unit = {
        r
      }
    }
  }
}
