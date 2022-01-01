/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.platform.mediaifc.ext

import akka.actor.Actor
import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.bus.ConsumerSupport
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataScanCompleted

object MediaIfcExtension {

  /**
    * A trait to be implemented by objects that need to register consumers.
    *
    * This trait is used to automate the registration of consumers at the
    * corresponding extension components. The idea is that UI components like
    * controllers extend this trait and return a collection of the
    * registrations they require. These registrations can then be published on
    * the message bus automatically.
    *
    * The platform offers support for defining such providers in Jelly scripts
    * and processing registrations automatically at application startup.
    */
  trait ConsumerRegistrationProvider {
    /**
      * Returns a collection with the consumer registrations supported by this
      * provider. This method is invoked once by the LineDJ platform. The
      * registration objects returned here are published on the message bus to
      * make the registrations effective.
      *
      * @return a collection with the ''ConsumerRegistration'' objects
      *         contributed by this provider
      */
    def registrations: Iterable[ConsumerRegistration[_]]
  }
}

/**
  * A trait defining base functionality for extensions for the interface to the
  * media archive.
  *
  * The interface to the media archive as defined by the ''MediaFacade'' trait
  * is pretty low-level and not easy to use for applications running on the
  * LineDJ platform. This is especially due to the fact that the interface is a
  * shared component, and responses received from the archive are published on
  * the message bus. So an application component registered at the message bus
  * receives a bunch of messages and cannot be sure whether these are sent as
  * responses for its own requests or on behalf of other applications.
  *
  * Another point is sharing of data from the archive within a LineDJ platform
  * instance. For instance, there may be multiple applications running on that
  * instance that are interested in the list of media available in the archive.
  * Having all these applications query this information themselves would
  * result in multiple requests and a waste of bandwidth.
  *
  * To address these problems, so-called extensions for the interface to the
  * media archive are introduced. An extension provides functionality on a
  * higher level related to a specific area or data structure of the media
  * archive. The basic idea is that extensions are registered as listeners at
  * the message bus and use this channel to obtain their requests. Typical
  * requests identify ''consumers'' for specific data. The consumer is stored
  * by the extension (and not directly registered at the message bus), and an
  * action is taken to obtain the data requested by the consumer. The data may
  * already be available if it has been requested before by another consumer.
  * Otherwise, it has to be fetched from the archive. When data is available
  * for a consumer it gets notified through a consumer function it has to
  * specify in its original request.
  *
  * This mechanism establishes another channel for sending specialized
  * messages to consumers by invoking their consumer function; concurrently,
  * normal communication with the media archive takes place via the message bus
  * without affecting consumers (which do not listen on this channel). Caching
  * or sharing of data can be achieved by storing responses from the archive
  * published on the message bus and propagating them to consumers.
  *
  * This trait is based on the functionality offered by [[ConsumerSupport]].
  * It adds some specialized functionality for extensions of the media archive
  * by processing messages related to the archive on the UI bus. It also
  * introduces some special callback methods for status changes of the media
  * archive.
  *
  * @tparam C the type of data this extension operates on
  * @tparam K the type for the grouping key used by this extension
  */
trait MediaIfcExtension[C, K] extends ConsumerSupport[C, K] with MessageBusListener {
  /**
    * @inheritdoc This implementation returns a combined message function.
    *             Some messages are already processed by this base class.
    *             Derived classes can override ''receiveSpecific()'' to
    *             implement their specific message handling.
    */
  override final def receive: Receive = receiveSpecific orElse receiveBase

  /**
    * A notification method that is invoked when receiving an event about the
    * availability of the media archive. When this happens, derived classes may
    * need to perform some actions such as resetting their state.
    *
    * @param hasConsumers a flag whether currently consumers are registered
    */
  def onArchiveAvailable(hasConsumers: Boolean): Unit = {}

  /**
    * A notification method that is invoked when receiving an event about a
    * completed media scan. This event might be of interest for derived classes
    * which may have to updated themselves for new data becoming available.
    *
    * @param hasConsumers a flag whether currently consumers are registered
    */
  def onMediaScanCompleted(hasConsumers: Boolean): Unit = {}

  /**
    * A message processing function that can be overridden by derived classes
    * to implement their own message handling. The ''receive()'' implementation
    * returns a concatenated function of this method and the base message
    * handling function.
    *
    * @return a message handling function for specific events
    */
  protected def receiveSpecific: Receive = Actor.emptyBehavior

  /**
    * A function for handling the base messages on the message bus.
    *
    * @return the base messaging function
    */
  private def receiveBase: Receive = {
    case MetaDataScanCompleted => onMediaScanCompleted(consumerMap.nonEmpty)
    case MediaFacade.MediaArchiveAvailable => onArchiveAvailable(consumerMap.nonEmpty)
  }
}

/**
  * A base trait which can be used for simple media interface extensions that
  * do not support grouping functionality.
  *
  * This trait sets the grouping type parameter to a fix value and provides a
  * corresponding default key. Extensions dealing with homogeneous consumers
  * can extend this trait.
  *
  * @tparam C the type of data this extension operates on
  */
trait NoGroupingMediaIfcExtension[C] extends MediaIfcExtension[C, AnyRef] {
  override val defaultKey: AnyRef = new Object
}
