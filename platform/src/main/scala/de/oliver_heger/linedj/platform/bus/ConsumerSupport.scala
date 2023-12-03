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

package de.oliver_heger.linedj.platform.bus

import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}

object ConsumerSupport:
  /**
    * A type defining a consumer function. A consumer function is invoked when
    * a specific result is received.
    *
    * @tparam C the result type for this consumer function
    */
  type ConsumerFunction[C] = C => Unit

  /**
    * A trait defining the registration of a consumer for specific data.
    *
    * The registration consists of the consumer ID (which is used for a later
    * un-registration) and the consumer function for passing data to this
    * consumer.
    *
    * @tparam C the type of data this consumer is interested in
    */
  trait ConsumerRegistration[C]:
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

    /**
      * Returns an object to be published on the event bus in order to remove
      * this consumer registration. This method can be used by an automatic
      * mechanism to remove consumer registrations when a component shuts down.
      *
      * @return an object to remove this consumer registration
      */
    def unRegistration: AnyRef

/**
  * A base trait offering functionality for the management of
  * ''consumers''.
  *
  * In the LineDJ platform communication between components often takes place
  * via the UI message bus. Status updates are announced by publishing specific
  * messages on this bus.
  *
  * Sometimes, multiple components are interested in keeping track about a
  * specific information. This could be achieved if all of them register
  * themselves as listeners on the message bus and evaluate the corresponding
  * state change notifications; but this would typically mean some duplicated
  * code and redundancy. There is also the problem that a listener registered
  * at a later point in time might miss important state messages.
  *
  * To solve this problem, specialized central components can be used that are
  * responsible for the processing of state change messages and keeping an
  * up-to-date state. Parties interested in this state can then register at
  * these central components and then receive convenient updates if there is a
  * change. Such parties are referred to as ''consumers'' of a specific
  * information.
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
  * Registered consumers can be categorized by a key. This is useful if they
  * are interested in a subset of data managed by an implementation only. If a
  * concrete implementation does not require grouping of its consumers, it can
  * rely on a default grouping key. Otherwise, the key has to be determined in
  * a way specific to the managed data.
  *
  * @tparam C the type of data this instance operates on
  * @tparam K the type for the grouping key used by this instance
  */
trait ConsumerSupport[C, K]:

  /**
    * Holds the consumers registered at this object.
    */
  private var consumers = Map.empty[K, Map[ComponentID, ConsumerFunction[C]]]

  /**
    * The default key used by this extension. This key is used if the caller
    * did not provide a key explicitly.
    */
  val defaultKey: K

  /**
    * Adds a consumer to this object. The return value is '''true''' if this is
    * the first consumer added to this object (i.e. the list of consumers
    * managed by this object was empty before) and '''false''' otherwise. This
    * can be used as hint by callers that some action may be necessary.
    * (One use case could be lazy initialization. Then the first consumer
    * added may trigger this initialization.) Note that it is not supported to
    * register a consumer with the same ID multiple times.
    *
    * @param reg the registration for the consumer
    * @param key the grouping key for the consumer
    * @return a flag whether this is the first consumer added to this object
    */
  def addConsumer(reg: ConsumerRegistration[C], key: K = defaultKey): Boolean =
    val wasEmpty = consumers.isEmpty
    val consumerGroup = fetchGroup(key) + (reg.id -> reg.callback)
    consumers += key -> consumerGroup
    onConsumerAdded(reg.callback, key, wasEmpty)
    wasEmpty

  /**
    * Removes the specified consumer from this object. The return value is
    * '''true''' if after this remove operation no more consumers are stored in
    * this object. (This is an important state change which might have to be
    * handled in a derived class.) If the specified ID does not match a
    * registered consumer, this operation has no effect.
    *
    * @param id  the ID of the consumer to be removed
    * @param key the grouping key for the consumer to be removed
    * @return a flag whether the last consumer was removed
    */
  def removeConsumer(id: ComponentID, key: K = defaultKey): Boolean =
    val consumerGroup = fetchGroup(key)
    val updatedConsumerGroup = consumerGroup - id
    if updatedConsumerGroup.isEmpty then
      consumers -= key
    else
      consumers += key -> updatedConsumerGroup
    val removed = updatedConsumerGroup.size < consumerGroup.size
    val last = consumers.isEmpty && removed
    if removed then
      onConsumerRemoved(key, last)
    last

  /**
    * Invokes the consumer functions of all registered consumers with the
    * specified data. Note that the order in which consumers are invoked is not
    * specified.
    *
    * @param data the data to be passed to registered consumers
    * @param key  the grouping key for the consumers to be notified
    */
  def invokeConsumers(data: => C, key: K = defaultKey): Unit =
    lazy val message = data
    consumerList(key) foreach (_ (message))

  /**
    * Removes all consumers of the specified group. Note that this method will
    * not call any callbacks for removed consumers. It is intended to be used
    * by subclasses under specific conditions; it assumes that the subclass
    * knows what it does.
    *
    * @param key the key of the consumer group to be removed
    * @return a flag whether actually consumers were removed (i.e. the group
    *         existed)
    */
  def removeConsumers(key: K = defaultKey): Boolean =
    val oldConsumers = consumers
    consumers = oldConsumers - key
    consumers.size < oldConsumers.size

  /**
    * Removes all consumers from this object. Note that this method will not
    * call any callbacks for removed consumers. Like ''removeConsumers()'' it
    * is assumed that the caller knows what it does.
    *
    * @return a flag whether actually consumers were removed (i.e. consumers
    *         had been registered)
    */
  def clearConsumers(): Boolean =
    val oldConsumers = consumers
    consumers = Map.empty
    oldConsumers.nonEmpty

  /**
    * Returns a sequence with all currently registered consumer functions
    * with the specified grouping key.
    *
    * @param key the grouping key for the desired consumers
    * @return an ''Iterable'' with all registered consumer functions
    */
  def consumerList(key: K = defaultKey): Iterable[ConsumerFunction[C]] =
    fetchGroup(key).values

  /**
    * Returns a map with all registered consumers. This can be used in derived
    * classes to manipulate consumers directly or to find out whether consumers
    * are present.
    *
    * @return a map with all registered consumers grouped by their key
    */
  def consumerMap: Map[K, Map[ComponentID, ConsumerFunction[C]]] = consumers

  /**
    * A notification method that is invoked when a new consumer was added. A
    * flag is passed whether this is the first consumer; in this case,
    * special actions may be required.
    *
    * @param cons  the consumer function that has been added
    * @param key   the key associated with the consumer
    * @param first a flag whether this is the first consumer
    */
  def onConsumerAdded(cons: ConsumerFunction[C], key: K, first: Boolean): Unit = {}

  /**
    * A notification method that is invoked when a consumer was removed. A flag
    * is passed whether this is the last consumer; in this case, special
    * actions may be required.
    *
    * @param key  the key associated with the consumer
    * @param last a flag whether this is the last consumer
    */
  def onConsumerRemoved(key: K, last: Boolean): Unit = {}

  /**
    * Convenience method for fetching a consumer group and returning an empty
    * map if it does not exist.
    *
    * @param key the key of the desired consumer group
    * @return the map of this consumer group (may be empty)
    */
  private def fetchGroup(key: K): Map[ComponentID, ConsumerFunction[C]] =
    consumers.getOrElse(key, Map.empty)
