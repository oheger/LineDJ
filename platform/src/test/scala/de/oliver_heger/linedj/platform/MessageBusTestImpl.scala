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

package de.oliver_heger.linedj.platform

import de.oliver_heger.linedj.platform.MessageBusTestImpl.{DirectCallGUISynchronizer, castMessage}
import de.oliver_heger.linedj.platform.bus.UIBus
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer
import org.apache.pekko.actor.Actor.Receive

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.reflect.ClassTag

object MessageBusTestImpl {

  /**
    * A dummy implementation of the ''GUISynchronizer'' interface which
    * executes the passed in task directly.
    */
  private class DirectCallGUISynchronizer extends GUISynchronizer {
    override def syncInvoke(runnable: Runnable): Unit = runnable.run()

    override def isEventDispatchThread: Boolean = true

    override def asyncInvoke(runnable: Runnable): Unit = syncInvoke(runnable)
  }

  /**
    * Helper function to cast a message of an unspecific type to a given target
    * type. This fails with a ''ClassCastException'' if the message has an
    * unexpected type.
    *
    * @param message the message
    * @param t       the ''ClassTag''
    * @tparam T the target type of the message
    * @return the message cast to the target type
    */
  private def castMessage[T](message: Any)(implicit t: ClassTag[T]): T =
    t.runtimeClass.asInstanceOf[Class[T]] cast message
}

/**
  * An implementation of ''MessageBus'' that can be used for tests.
  *
  * This implementation allows normal registrations of message bus listeners
  * and message publishing. However, listeners are invoked directly on the
  * current thread.
  *
  * There are two operation modes: messages published via the bus can either
  * be directly propagated to registered listeners or they can be queued,
  * inspected, and delivered on demand. In addition, a flag can be set whether
  * callbacks to complete futures on the UI thread should be handled directly.
  * The initial flag values are determined via  constructor parameters, but
  * they can be changed later on at any time.
  *
  * @param initDirectProcessing   the initial direct processing mode
  * @param initUIFutureProcessing the initial UI future processing mode
  * @param timeout                a timeout when waiting for messages (in seconds)
  */
class MessageBusTestImpl(initDirectProcessing: Boolean = false,
                         initUIFutureProcessing: Boolean = false,
                         timeout: Int = 3)
  extends UIBus(new DirectCallGUISynchronizer) {
  /**
    * A flag whether messages published to the bus should be delivered
    * directly. If set to '''false''', messages are only queued.
    */
  var directProcessing: Boolean = initDirectProcessing

  /**
    * A flag whether callbacks for UI future processing are directly forwarded
    * by the message bus.
    */
  var uiFutureProcessing: Boolean = initUIFutureProcessing

  /** A queue for recording messages. */
  private val messageQueue = new LinkedBlockingQueue[Any]

  /** Stores the listeners registered so that they can be queried. */
  private var registeredListeners = Map.empty[Int, Receive]

  /**
    * @inheritdoc This implementation takes the ''directProcessing'' flag into
    *             account.
    */
  override def publish(msg: Any): Unit = {
    if (shouldForward(msg)) super.publish(msg)
    else messageQueue offer msg
  }

  /**
    * Delivers the provided message directly to receivers, no matter of the
    * ''directProcessing'' flag.
    *
    * @param msg the message
    */
  def publishDirectly(msg: Any): Unit = {
    super.publish(msg)
  }

  /**
    * @inheritdoc This implementation records this invocation.
    */
  override def registerListener(r: Receive): Int = {
    val regId = super.registerListener(r)
    registeredListeners += regId -> r
    regId
  }

  /**
    * @inheritdoc This implementation records this invocation.
    */
  override def removeListener(listenerID: Int): Unit = {
    registeredListeners -= listenerID
    super.removeListener(listenerID)
  }

  /**
    * Returns a map with all listeners currently registered at the bus. The
    * map has listener IDs as keys and the listeners as values.
    *
    * @return a map with all listeners currently registered
    */
  def currentListeners: Map[Int, Receive] = registeredListeners

  /**
    * Returns a collection of all message listener functions than can process
    * the specified message. This is useful to select a specific handler if
    * there are multiple message listener registrations.
    *
    * @param msg the message in question
    * @return a collection with all listener functions supporting the message
    */
  def findListenersForMessage(msg: Any): Iterable[Receive] =
    currentListeners.values.filter(_.isDefinedAt(msg))

  /**
    * Returns an ''Option'' with a message listener function registered at
    * this bus that can handle the specified message. If there are multiple
    * such functions, an arbitrary one is selected.
    *
    * @param msg the message in question
    * @return an ''Option'' with the function supporting this message
    */
  def findListenerForMessage(msg: Any): Option[Receive] =
    findListenersForMessage(msg).headOption

  /**
    * Expects that a message of the specified type has been published to the
    * bus and returns it. Fails with a ''NoSuchElementException'' if this is
    * not the case. This method only works if direct processing is disabled.
    *
    * @param t the ''ClassTag''
    * @tparam T the type of the message
    * @return the message
    * @throws NoSuchElementException if no message has been received
    */
  def expectMessageType[T](implicit t: ClassTag[T]): T = {
    val m = messageQueue.poll(timeout, TimeUnit.SECONDS)
    if (m == null) {
      throw new NoSuchElementException("No message received within timeout!")
    }
    castMessage(m)
  }

  /**
    * Searches for a message of the given type in the published messages.
    * Messages of different types are ignored. Returns the first message of the
    * specified type or fails if no more messages are found.
    *
    * @param t the ''ClassTag''
    * @tparam T the type of the desired message
    * @return the first message of the given type
    * @throws NoSuchElementException if no message of this type was found
    */
  @tailrec final def findMessageType[T](implicit t: ClassTag[T]): T = {
    val message = expectMessageType[Any]
    if (t.runtimeClass.isInstance(message)) castMessage(message)
    else findMessageType[T]
  }

  /**
    * Expects that no message is published on the message bus in a given
    * time frame.
    *
    * @param timeout the timeout
    */
  def expectNoMessage(timeout: FiniteDuration = 1.second): Unit = {
    val m = messageQueue.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
    if (m != null) {
      throw new IllegalStateException("Unexpected message: " + m)
    }
  }

  /**
    * Delivers the next received message to registered listeners and returns
    * it. Fails if the message is of a different type or no message has been
    * received. This method only works if direct processing is disabled.
    *
    * @param t the ''ClassTag''
    * @tparam T the type of the message
    * @return the message
    * @throws NoSuchElementException if no message has been received
    */
  def processNextMessage[T]()(implicit t: ClassTag[T]): T = {
    val msg = expectMessageType[T]
    publishDirectly(msg)
    msg
  }

  /**
    * Clears all the messages that might have been recorded.
    */
  def clearMessages(): Unit = {
    messageQueue.clear()
  }

  /**
    * Checks whether the given message should be forwarded directly on the bus.
    * This depends on some flags.
    *
    * @param msg the message
    * @return a flag whether this message should be handled directly
    */
  private def shouldForward(msg: Any): Boolean =
    directProcessing || (uiFutureProcessing && msg.getClass.getName.endsWith("FutureUICallback"))
}
