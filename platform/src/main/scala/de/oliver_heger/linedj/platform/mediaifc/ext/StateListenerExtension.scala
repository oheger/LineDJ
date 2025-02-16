/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.bus.{ComponentID, Identifiable}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.StateListenerExtension.{StateListenerRegistration, StateListenerUnregistration}
import de.oliver_heger.linedj.shared.archive.metadata.{MetadataStateEvent, MetadataStateUpdated}
import org.apache.pekko.actor.Actor.Receive

object StateListenerExtension:

  /**
    * A message class representing a metadata state consumer registration.
    * In order to register a state event consumer, this message has to be sent
    * on the message bus.
    *
    * @param id       the ID of the consumer
    * @param callback the consumer callback
    */
  case class StateListenerRegistration(override val id: ComponentID,
                                       override val callback: ConsumerFunction[MetadataStateEvent])
    extends ConsumerRegistration[MetadataStateEvent]:
    override def unRegistration: AnyRef = StateListenerUnregistration(id)

  /**
    * A message class used to remove a state listener removed. When
    * [[StateListenerExtension]] receives such a message, the referenced
    * consumer is removed.
    *
    * @param id the ID of the consumer
    */
  case class StateListenerUnregistration(id: ComponentID)

/**
  * A specific extension for the media archive interface for the management of
  * metadata state listeners.
  *
  * This class holds consumers for ''MetaDataStateEvent'' notifications. When
  * at least one consumer is added it triggers a state listener registration at
  * the ''MediaFacade''. Incoming state events are then propagated to
  * consumers. If the connection to the archive is lost and reestablished
  * later, another registration is triggered as necessary.
  *
  * To make sure that newly added consumers are correctly initialized, the last
  * received ''MetaDataStateUpdated'' event is cached and passed to new
  * consumers. This is in-line with the "real" listener registration at the
  * media archive.
  *
  * @param mediaFacade the facade to the media archive
  */
class StateListenerExtension(val mediaFacade: MediaFacade)
  extends NoGroupingMediaIfcExtension[MetadataStateEvent] with Identifiable:
  /** The last update state event received from the archive. */
  private var lastUpdatedEvent: Option[MetadataStateEvent] = None

  /**
    * @inheritdoc This implementation adds a new listener registration if
    *             necessary.
    */
  override def onArchiveAvailable(hasConsumers: Boolean): Unit =
    lastUpdatedEvent = None

  /**
    * @inheritdoc This implementation creates a state listener registration for
    *             the first consumer.
    */
  override def onConsumerAdded(cons: ConsumerFunction[MetadataStateEvent], key: AnyRef,
                               first: Boolean): Unit =
    registerStateListenerIfRequired(first)
    lastUpdatedEvent foreach cons

  /**
    * @inheritdoc This implementation removes the current state listener
    *             registration after the last consumer is gone.
    */
  override def onConsumerRemoved(key: AnyRef, last: Boolean): Unit =
    if last then
      mediaFacade.unregisterMetadataStateListener(componentID)
      lastUpdatedEvent = None

  /**
    * @inheritdoc This implementation reacts on state events and passes them to
    *             consumers.
    */
  override protected def receiveSpecific: Receive =
    case up: MetadataStateUpdated =>
      lastUpdatedEvent = Some(up)
      invokeConsumers(up)

    case ev: MetadataStateEvent => invokeConsumers(ev)

    case reg: StateListenerRegistration => addConsumer(reg)

    case StateListenerUnregistration(id) => removeConsumer(id)

  /**
    * Creates a state listener registration if consumers are currently present.
    *
    * @param hasConsumers flag whether consumers are present
    */
  private def registerStateListenerIfRequired(hasConsumers: Boolean): Unit =
    if hasConsumers then
      mediaFacade.registerMetadataStateListener(componentID)
