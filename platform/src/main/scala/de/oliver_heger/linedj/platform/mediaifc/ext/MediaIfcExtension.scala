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

package de.oliver_heger.linedj.platform.mediaifc.ext

import akka.actor.Actor
import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataScanCompleted

object MediaIfcExtension {
  /**
    * A type defining a consumer function. A consumer function is invoked when
    * a specific result is received.
    *
    * @tparam C the result type for this consumer function
    */
  type ConsumerFunction[C] = C => Unit

  /**
    * A trait defining an ID of a consumer.
    *
    * The ID uniquely identifies a consumer and has to be provided during
    * consumer registration and removal. Having a dedicated trait for this
    * purpose aims at a better isolation of consumers. If for instance
    * consumers were identified using their Object reference, a malicious
    * application would have a chance to obtain this reference through a
    * different means at use it for an uncontrolled un-registration. A concrete
    * ''ConsumerID'' object in contrast could contain some random data which
    * could not simply be guessed.
    */
  trait ConsumerID

  /**
    * A trait defining an abstract factory for consumer IDs.
    *
    * This trait defines a method that expects an object reference
    * (representing the consumer or an object that is associated with it) and
    * returns a new ''ConsumerID''.
    *
    * Concrete implementations are responsible to create unique IDs, even if
    * the same object reference is passed in multiple times; so there should be
    * a certain kind of randomness. This is also important to avoid that
    * different components running on the platform can interfere with each
    * other (on accident or - in case of a malicious component - on intention).
    */
  trait ConsumerIDFactory {
    /**
      * Creates a new ''ConsumerID'' for the specified owner.
      *
      * @param owner the owner of the ID; typically an object associated
      *              somehow with the consumer to be identified
      * @return the newly created ''ConsumerID''
      */
    def createID(owner: AnyRef): ConsumerID
  }

  /**
    * A trait defining the registration of a consumer for specific data.
    *
    * The registration consists of the consumer ID (which is used for a later
    * un-registration) and the consumer function for passing data to this
    * consumer.
    *
    * @tparam C the type of data this consumer is interested in
    */
  trait ConsumerRegistration[C] {
    /**
      * Returns the ID of this consumer (which is a ''ComponentID'').
      *
      * @return the unique consumer ID
      */
    def id: ComponentID

    /**
      * Returns the callback function of this consumer. Via this function
      * results are propagated to this consumer.
      *
      * @return this consumer's callback function
      */
    def callback: ConsumerFunction[C]
  }

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
  * for an consumer it gets notified through a consumer function it has to
  * specify in its original request.
  *
  * This mechanism establishes another channel for sending specialized
  * messages to consumers by invoking their consumer function; concurrently,
  * normal communication with the media archive takes place via the message bus
  * without affecting consumers (which do not listen on this channel). Caching
  * or sharing of data can be achieved by storing responses from the archive
  * published on the message bus and propagating them to consumers.
  *
  * This trait and its companion object define some data types related to
  * consumers and implement basic functionality for managing a list of
  * consumers of a specific type. Subclasses then add actual data management
  * and pass results to registered consumers.
  *
  * Consumers are expected to be UI-related components. Therefore, all
  * messaging happens in the UI thread - which is the case anyway for messages
  * published on the message bus. This implementation does not contain any
  * synchronization; it expects to be called on the UI thread only.
  *
  * @tparam C the type of data this extension operates on
  */
trait MediaIfcExtension[C] extends MessageBusListener {
  /**
    * Holds the consumers registered at this object.
    */
  private var consumers = Map.empty[ComponentID, ConsumerFunction[C]]

  /**
    * @inheritdoc This implementation returns a combined message function.
    *             Some messages are already processed by this base class.
    *             Derived classes can override ''receiveSpecific()'' to
    *             implement their specific message handling.
    */
  override final def receive: Receive = receiveSpecific orElse receiveBase

  /**
    * Adds a consumer to this object. The return value is '''true''' if this is
    * the first consumer added to this object (i.e. the list of consumers
    * managed by this object was empty before) and '''false''' otherwise. This
    * can be used as hint by callers that some action may be necessary.
    * (Typically extensions use lazy initialization. So the first consumer
    * added may trigger this initialization.) Note that it is not supported to
    * register a consumer with the same ID multiple times.
    *
    * @param reg the registration for the consumer
    * @return a flag whether this is the first consumer added to this object
    */
  def addConsumer(reg: ConsumerRegistration[C]): Boolean = {
    consumers += reg.id -> reg.callback
    val first = consumers.size == 1
    onConsumerAdded(reg.callback, first)
    first
  }

  /**
    * Removes the specified consumer from this object. The return value is
    * '''true''' if after this remove operation no more consumers are stored in
    * this object. (This is an important state change which might have to be
    * handled in a derived class.) If the specified ID does not match a
    * registered consumer, this operation has no effect.
    *
    * @param id the ID of the consumer to be removed
    * @return a flag whether the last consumer was removed
    */
  def removeConsumer(id: ComponentID): Boolean = {
    val sizeBefore = consumers.size
    consumers -= id
    val last = consumers.isEmpty && sizeBefore > 0
    if (consumers.size < sizeBefore) {
      onConsumerRemoved(last)
    }
    last
  }

  /**
    * Invokes the consumer functions of all registered consumers with the
    * specified data. Note that the order in which consumers are invoked is not
    * specified.
    *
    * @param data the data to be passed to registered consumers
    */
  def invokeConsumers(data: => C): Unit = {
    lazy val message = data
    consumerList foreach (_ (message))
  }

  /**
    * Returns a sequence with all currently registered consumer functions.
    *
    * @return an ''Iterable'' with all registered consumer functions
    */
  def consumerList: Iterable[ConsumerFunction[C]] = consumers.values

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
    * A notification method that is invoked when a new consumer was added. A
    * flag is passed whether this is the first consumer; in this case,
    * special actions may be required.
    *
    * @param cons  the consumer function that has been added
    * @param first a flag whether this is the first consumer
    */
  def onConsumerAdded(cons: ConsumerFunction[C], first: Boolean): Unit = {}

  /**
    * A notification method that is invoked when a consumer was removed. A flag
    * is passed whether this is the last consumer; in this case, special
    * actions may be required.
    *
    * @param last a flag whether this is the last consumer
    */
  def onConsumerRemoved(last: Boolean): Unit = {}

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
    case MetaDataScanCompleted => onMediaScanCompleted(consumers.nonEmpty)
    case MediaFacade.MediaArchiveAvailable => onArchiveAvailable(consumers.nonEmpty)
  }
}
