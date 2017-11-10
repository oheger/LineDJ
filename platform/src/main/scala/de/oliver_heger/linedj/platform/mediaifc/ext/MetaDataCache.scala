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

package de.oliver_heger.linedj.platform.mediaifc.ext

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{MetaDataChunk, MetaDataResponse}
import de.oliver_heger.linedj.utils.LRUCache
import org.slf4j.LoggerFactory

object MetaDataCache {
  /**
    * A message class processed by [[MetaDataCache]] to add a registration for the
    * meta data of a medium.
    *
    * With this message a component indicates its interest on the meta data of the
    * specified medium. Whenever meta data becomes available it is passed to the
    * callback function provided in the message.
    *
    * @param mediumID the ID of the medium
    * @param id       a unique ID to identify this listener; this can later be
    *                 used to remove the registration again
    * @param callback the callback function
    */
  case class MetaDataRegistration(mediumID: MediumID, override val id: ComponentID,
                                  override val callback: ConsumerFunction[MetaDataChunk])
    extends ConsumerRegistration[MetaDataChunk]

  /**
    * A message class processed by [[MetaDataCache]] to remove the registration
    * for the meta data of a medium.
    *
    * When receiving a message of this type the cache will remove the represented
    * listener registration. This means that this listener will receive no further
    * messages for this medium.
    *
    * @param mediumID the ID of the medium
    * @param listenerID the unique listener ID
    */
  case class RemoveMetaDataRegistration(mediumID: MediumID, listenerID: ComponentID)

  /** Constant for the chunk for an unknown medium. */
  private val UndefinedChunk = MetaDataChunk(null, Map.empty, complete = false)

  /**
    * The function that determines the size of items in the meta data LRU
    * cache.
    *
    * @param chunk the chunk in question
    * @return the size of this item in the cache
    */
  private def cacheSizeFunc(chunk: MetaDataChunk): Int = chunk.data.size
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
 * of a medium sends a
 * [[de.oliver_heger.linedj.platform.mediaifc.ext.MetaDataCache.MetaDataRegistration]]
 * message on the message bus. The
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
 * If the connection to the archive is lost and later reestablished, the cache
 * is cleared, and all registered listeners are removed.
  *
  * To prevent that the memory used by the cache grows without limits,  a
  * maximum number of meta data items to be stored can be specified. Note,
  * however, that this is not a hard limit, but works with the following
  * restrictions:
  *
  *  - at least a full medium is stored in the cache. So if this medium has
  * more songs than the maximum size parameter, this limit is ignored.
  *  - The items of requested media remain in the cache until the medium has
  * been completely loaded. So if clients request many media in parallel, the
  * cache's size can (temporarily) grow over the specified limit.
  *
  * The size restriction works on medium level: When new meta data is added to
  * the cache and the cache size grows over the limit, the medium with the
  * oldest access time is searched and removed. (The cache operates in a LRU
  * mode.) This is repeated until the size limit can be met or no suitable
  * medium to be removed (according to the mentioned restrictions) can be
  * found.
 *
 * @param mediaFacade the facade to the media archive
  * @param cacheSize the size restriction for this cache (see class
  *                  documentation for more information)
 */
class MetaDataCache(val mediaFacade: MediaFacade, val cacheSize: Int)
  extends MediaIfcExtension[MetaDataChunk, MediumID] {

  import MetaDataCache._

  /** Logger. */
  private val log = LoggerFactory.getLogger(getClass)

  /** The cache with the already received meta data chunks per medium. */
  private var receivedChunks = createMetaDataCache()

  /** A map storing registration IDs for the requested media. */
  private var registrationIDs = Map.empty[MediumID, Int]

  /**
    * A default key for this extension. This is typically not used.
    */
  override val defaultKey: MediumID = MediumID.UndefinedMediumID

  /**
    * Returns the current number of entries contained in this cache. Note that
    * as described in the class comment, this number may temporarily exceed the
    * configured cache size.
    *
    * @return the current number of entries in the cache
    */
  def numberOfEntries: Int = receivedChunks.size

  override protected def receiveSpecific: Receive = {
    case registration: MetaDataRegistration =>
      handleRegistration(registration)

    case MetaDataResponse(chunk, regID) =>
      metaDataReceived(chunk, regID)

    case RemoveMetaDataRegistration(mediumID, listenerID) =>
      handleUnRegistration(mediumID, listenerID)

    case MediaFacade.MediaArchiveUnavailable =>
      clearConsumers()
  }

  /**
    * Handles the registration of a meta data listener. If necessary, the
    * medium is requested from the archive. Access to this medium moves it to
    * the front of the meta data cache; so it will not be directly removed if
    * the cache becomes too large.
    *
    * @param registration the registration to be handled
    */
  private def handleRegistration(registration: MetaDataRegistration): Unit = {
    val currentChunk = receivedChunks.getOrElse(registration.mediumID,
      UndefinedChunk)
    if (!currentChunk.complete) {
      addConsumer(registration, registration.mediumID)
      if (currentChunk eq UndefinedChunk) {
        val regID = mediaFacade.queryMetaDataAndRegisterListener(registration.mediumID)
        registrationIDs += registration.mediumID -> regID
      }
    }
    if (currentChunk.data.nonEmpty) {
      registration.callback(currentChunk)
    }
  }

  /**
    * Handles a request to remove a meta data listener for a medium. If there
    * are no more remaining listeners for this medium, the facade can be notified
    * to stop tracking it for this client. If the medium ID or the listener ID
    * cannot be resolved, this operation has no effect.
    *
    * @param mediumID   the ID of the medium
    * @param listenerID the ID of the listener
    */
  private def handleUnRegistration(mediumID: MediumID, listenerID: ComponentID): Unit = {
    val oldConsumers = consumerList(mediumID)
    removeConsumer(listenerID, mediumID)
    if (consumerList(mediumID).isEmpty && oldConsumers.nonEmpty) {
      mediaFacade.removeMetaDataListener(mediumID)
      registrationIDs -= mediumID
      receivedChunks removeItem mediumID
    }
  }

  /**
    * Processes meta data that was received from the archive. The new chunk of
    * data is combined with data already stored in the cache. Listeners
    * interested in the affected medium are notified. If the cache becomes too
    * large, media which have not been accessed recently are removed.
    *
    * @param chunk the new chunk of data
    * @param regID the registration ID
    */
  private def metaDataReceived(chunk: MetaDataChunk, regID: Int): Unit = {
    if (regID == registrationID(chunk.mediumID)) {
      if(receivedChunks contains chunk.mediumID) {
        receivedChunks.updateItem(chunk.mediumID)(combine(_, chunk))
      } else {
        receivedChunks.addItem(chunk.mediumID, chunk)
          log.info("Added medium {} to cache.", chunk.mediumID)
      }

      invokeConsumers(chunk, chunk.mediumID)
      if (chunk.complete) {
        removeConsumers(chunk.mediumID)
        registrationIDs -= chunk.mediumID
      }
    }
  }

  /**
    * @inheritdoc This implementation removes the cache as it might contain
    *             stale data.
    */
  override def onArchiveAvailable(hasConsumers: Boolean): Unit = {
    receivedChunks = createMetaDataCache()
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

  /**
    * Returns the expected registration ID for the given medium. This is used
    * to detect outdated meta data messages.
    *
    * @param mediumID the medium ID
    * @return the registration ID for this medium
    */
  private def registrationID(mediumID: MediumID): Int =
  registrationIDs.getOrElse(mediumID, MediaFacade.InvalidListenerRegistrationID)

  /**
    * A function that determines whether an item from the meta data cache can
    * be removed. Only items can be removed, for which no consumers are
    * registered, i.e. which have been completely downloaded.
    *
    * @param chunk the chunk in question
    * @return a flag whether this item can be removed from the cache
    */
  private def cacheRemovableFunc(chunk: MetaDataChunk): Boolean = {
    val canRemove = consumerList(chunk.mediumID).isEmpty
    if (canRemove) {
      log.info("Removing medium {} from meta data cache.", chunk.mediumID)
    }
    canRemove
  }

  /**
    * Creates the meta data cache.
    *
    * @return the new cache
    */
  private def createMetaDataCache(): LRUCache[MediumID, MetaDataChunk] =
    new LRUCache[MediumID, MetaDataChunk](cacheSize)(sizeFunc =
      cacheSizeFunc, removableFunc = cacheRemovableFunc)
}
