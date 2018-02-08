/*
 * Copyright 2015-2018 The Developers Team.
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
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.bus.{ComponentID, Identifiable}
import de.oliver_heger.linedj.platform.mediaifc.ext.AvailableMediaExtension.{AvailableMediaRegistration, AvailableMediaUnregistration}
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, GetAvailableMedia}
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataScanStarted

object AvailableMediaExtension {

  /**
    * A message class representing the registration of a consumer for
    * [[AvailableMedia]] objects. In order to add a consumer to the
    * [[AvailableMediaExtension]], such an object has to be published on the
    * message bus.
    *
    * @param id       the consumer ID
    * @param callback the consumer callback
    */
  case class AvailableMediaRegistration(override val id: ComponentID,
                                        override val callback: ConsumerFunction[AvailableMedia])
    extends ConsumerRegistration[AvailableMedia] {
    override def unRegistration: AnyRef = AvailableMediaUnregistration(id)
  }

  /**
    * A message class used to remove a consumer for [[AvailableMedia]] objects.
    * When [[AvailableMediaExtension]] receives such a message the referenced
    * consumer is removed.
    *
    * @param id the consumer ID
    */
  case class AvailableMediaUnregistration(id: ComponentID)

}

/**
  * A specific extension for the media archive interface that handles
  * consumers for the currently available media.
  *
  * This class manages an instance of
  * [[de.oliver_heger.linedj.shared.archive.media.AvailableMedia]]. When the
  * media archive is available and consumers are registered a request to query
  * the available media is sent to the archive. The resulting data is cached
  * and propagated to registered consumers.
  *
  * When another scan for media is completed or the archive becomes available
  * again after a connection loss the available media are queried again to
  * make sure that the data is up-to-date.
  *
  * @param mediaFacade the facade to the media archive
  */
class AvailableMediaExtension(val mediaFacade: MediaFacade)
  extends NoGroupingMediaIfcExtension[AvailableMedia] with Identifiable {
  /** A cache for the current available media data. */
  private var currentMediaData: Option[AvailableMedia] = None

  /** A flag whether a request for media data is pending. */
  private var requestPending = false

  override protected def receiveSpecific: Receive = {
    case reg: AvailableMediaRegistration =>
      addConsumer(reg)

    case AvailableMediaUnregistration(id) => removeConsumer(id)

    case media: AvailableMedia =>
      invokeConsumers(media)
      currentMediaData = Some(media)

    case MetaDataScanStarted =>
      onMediaScanStarted(consumerMap.nonEmpty)
  }

  /**
    * @inheritdoc This implementation initializes the new consumer if media
    *             data is already available. Also, a meta data state listener
    *             registration must be active if consumers are present.
    */
  override def onConsumerAdded(cons: ConsumerFunction[AvailableMedia], key: AnyRef,
                               first: Boolean): Unit = {
    currentMediaData match {
      case Some(data) =>
        cons(data)
      case None =>
        if (!requestPending) {
          requestMediaData()
        }
    }
    if (first) {
      // We might already be registered, but this is handled correctly by the
      // media facade.
      registerStateListener()
    }
  }

  /**
    * @inheritdoc This implementation clears the internal cache. If consumers
    *             are registered, new data is requested.
    */
  override def onArchiveAvailable(hasConsumers: Boolean): Unit = {
    resetAndRequestNewData(hasConsumers)
  }

  /**
    * A new media scan was started. This means that data stored by this object
    * is stale now. So the internal cache is cleared. If consumers are
    * registered, new data is requested. Otherwise, a state listener
    * registration can now be removed.
    */
  private def onMediaScanStarted(hasConsumers: Boolean): Unit = {
    if (!resetAndRequestNewData(hasConsumers)) {
      // There might be no registration, but this is handled by the media
      // facade.
      mediaFacade.unregisterMetaDataStateListener(componentID)
    }
  }

  /**
    * Clears the internal cache and requests new data if necessary. This method
    * is invoked when new data in the archive becomes available, e.g. because
    * the connection to the archive is established or another scan has run.
    *
    * @param hasConsumers a flag whether consumers are available
    * @return the ''hasConsumers'' flag
    */
  private def resetAndRequestNewData(hasConsumers: Boolean): Boolean = {
    currentMediaData = None
    requestPending = false
    if (hasConsumers) {
      requestMediaData()
    }
    hasConsumers
  }

  /**
    * Sends a request for media data to the archive.
    */
  private def requestMediaData(): Unit = {
    mediaFacade.send(MediaActors.MediaManager, GetAvailableMedia)
    requestPending = true
  }

  /**
    * Adds a registration for a meta data state listener.
    */
  private def registerStateListener(): Unit = {
    mediaFacade.registerMetaDataStateListener(componentID)
  }
}
