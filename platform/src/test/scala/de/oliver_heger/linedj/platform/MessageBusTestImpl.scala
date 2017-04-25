/*
 * Copyright 2015-2017 The Developers Team.
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

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import de.oliver_heger.linedj.platform.MessageBusTestImpl.DirectCallGUISynchronizer
import de.oliver_heger.linedj.platform.bus.UIBus
import net.sf.jguiraffe.gui.builder.utils.GUISynchronizer

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
  * inspected, and delivered on demand. The initial mode is determined via a
  * constructor parameter, but it can be changed later on at any time.
  *
  * @param initDirectProcessing the initial direct processing mode
  * @param timeout              a timeout when waiting for messages (in seconds)
  */
class MessageBusTestImpl(initDirectProcessing: Boolean = false, timeout: Int = 3)
  extends UIBus(new DirectCallGUISynchronizer) {
  /**
    * A flag whether messages published to the bus should be delivered
    * directly. If set to '''false''', messages are only queued.
    */
  var directProcessing: Boolean = initDirectProcessing

  /** A queue for recording messages. */
  private val messageQueue = new LinkedBlockingQueue[Any]

  /**
    * @inheritdoc This implementation takes the ''directProcessing'' flag into
    *             account.
    */
  override def publish(msg: Any): Unit = {
    if (directProcessing) super.publish(msg)
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
    t.runtimeClass.asInstanceOf[Class[T]] cast m
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
}