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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade.{MediaArchiveAvailabilityEvent, MediaArchiveUnavailable}
import de.oliver_heger.linedj.platform.mediaifc.ext.ArchiveAvailabilityExtension.{ArchiveAvailabilityRegistration, ArchiveAvailabilityUnregistration}

object ArchiveAvailabilityExtension {

  /**
    * A message class representing the registration of a consumer for archive
    * availability events. In order to register new consumers, an instance of
    * this class has to be published on the message bus.
    *
    * @param id       the consumer ID
    * @param callback the consumer callback function
    */
  case class ArchiveAvailabilityRegistration(override val id: ComponentID, override val callback:
  ConsumerFunction[MediaArchiveAvailabilityEvent]) extends
    ConsumerRegistration[MediaArchiveAvailabilityEvent] {
    override def unRegistration: AnyRef = ArchiveAvailabilityUnregistration(id)
  }

  /**
    * A message class for removing consumers for archive availability events.
    *
    * @param id the ID of the consumer to be removed
    */
  case class ArchiveAvailabilityUnregistration(id: ComponentID)

}

/**
  * A specific extension for the media archive interface for the management of
  * consumers interested in the availability of the media archive.
  *
  * This class propagates messages related to the availability of the media
  * archive to registered consumers. So registered consumers can find out
  * whether the media archive is currently available or not. The current state
  * is passed to a newly registered consumer, so that it can be sure that it is
  * up-to-date.
  *
  * Consumer registrations are done by sending the corresponding messages (as
  * defined by the companion object) on the message bus.
  */
class ArchiveAvailabilityExtension
  extends NoGroupingMediaIfcExtension[MediaArchiveAvailabilityEvent] {
  /** The last known state of the media archive. */
  private var archiveState: MediaArchiveAvailabilityEvent = MediaArchiveUnavailable

  /**
    * @inheritdoc This implementation initializes the new consumer with the
    *             current archive state.
    */
  override def onConsumerAdded(cons: ConsumerFunction[MediaArchiveAvailabilityEvent],
                               key: AnyRef, first: Boolean): Unit = {
    cons(archiveState)
  }

  override protected def receiveSpecific: Receive = {
    case reg: ArchiveAvailabilityRegistration =>
      addConsumer(reg)

    case ArchiveAvailabilityUnregistration(id) => removeConsumer(id)

    case ev: MediaArchiveAvailabilityEvent =>
      invokeConsumers(ev)
      archiveState = ev
  }
}
