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

package de.oliver_heger.linedj.browser.cache

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MetaDataChunk

object MetaDataCache {
  /** Constant for the chunk for an unknown medium. */
  private val UndefinedChunk = MetaDataChunk(null, Map.empty, complete = false)
}

/**
 * A specialized media interface extension implementing a client-side cache for
  * meta data.
 *
 * Clients may request meta data for one and the same medium multiple times; it
 * therefore makes sense to cache it locally. This is done by this class. It
 * stores chunks of meta data received from the archive and is also able to
 * combine them; this is done in form of [[MetaDataChunk]] objects.
 *
 * This class is not directly accessed by other classes. Rather, interaction
 * takes place via the message bus: A component needing access to the meta data
 * of a medium sends a [[MetaDataRegistration]] message on the message bus. The
 * message contains a callback through which the sender can be notified about
 * incoming meta data. If data for this medium is contained in the cache, the
 * callback is directly triggered. Whether the caller is registered depends on
 * the availability of meta data: if the whole medium is contained in the
 * cache, no registration is needed as all information has already been passed
 * via the callback. Otherwise, the caller is registered and receives
 * notifications about incoming meta data chunks. When all chunks for this
 * medium have been received the registration is automatically removed.
 *
 * Some other interactions via the message bus are supported as well. For
 * instance, a client component can indicate that it is not longer
 * interested on the data of a certain medium. If necessary, this class then
 * sends a message for removing the corresponding medium listener.
 *
 * If the connection to the server is lost and later reestablished, the cache
 * is cleared, and all registered listeners are removed.
 *
 * @param mediaFacade the facade to the media archive
 */
class MetaDataCache(mediaFacade: MediaFacade)
  extends MediaIfcExtension[MetaDataChunk, MediumID] {

  import MetaDataCache._

  /** A map with the already received meta data chunks per medium. */
  private var receivedChunks = Map.empty[MediumID, MetaDataChunk]

  /**
    * A default key for this extension. This is typically not used.
    */
  override val defaultKey: MediumID = MediumID.UndefinedMediumID

  override protected def receiveSpecific: Receive = {
    case registration: MetaDataRegistration =>
      val currentChunk = receivedChunks.getOrElse(registration.mediumID, UndefinedChunk)
      if (!currentChunk.complete) {
        addConsumer(registration, registration.mediumID)
        if (currentChunk eq UndefinedChunk) {
          mediaFacade.queryMetaDataAndRegisterListener(registration.mediumID)
        }
      }
      if (currentChunk.data.nonEmpty) {
        registration.callback(currentChunk)
      }

    case chunk: MetaDataChunk =>
      val combinedChunk = receivedChunks.get(chunk.mediumID)
        .map(combine(_, chunk)) getOrElse chunk
      receivedChunks = receivedChunks + (chunk.mediumID -> combinedChunk)
      invokeConsumers(chunk, chunk.mediumID)

      if (chunk.complete) {
        removeConsumers(chunk.mediumID)
      }

    case RemoveMetaDataRegistration(mediumID, listenerID) =>
      val oldConsumers = consumerList(mediumID)
      removeConsumer(listenerID, mediumID)
      if (consumerList(mediumID).isEmpty && oldConsumers.nonEmpty) {
        mediaFacade.removeMetaDataListener(mediumID)
      }

    case MediaFacade.MediaArchiveUnavailable =>
      clearConsumers()
  }

  /**
    * @inheritdoc This implementation removes the cache as it might contain
    *             stale data.
    */
  override def onArchiveAvailable(hasConsumers: Boolean): Unit = {
    receivedChunks = Map.empty
  }

  /**
    * Combines two chunks of meta data to a single one.
    *
    * @param chunk1 chunk 1
    * @param chunk2 chunk 2
    * @return the combined chunk
    */
  private def combine(chunk1: MetaDataChunk, chunk2: MetaDataChunk): MetaDataChunk =
  chunk1.copy(data = chunk1.data ++ chunk2.data)
}
