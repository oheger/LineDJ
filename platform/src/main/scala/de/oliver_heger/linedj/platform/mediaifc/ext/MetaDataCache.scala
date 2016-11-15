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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.{MetaDataChunk, MetaDataResponse}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

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
    * Internally used class to construct a double-linked list of meta data
    * chunks. This is needed to implement LRU functionality for the cache.
    */
  private class ChunkItem {
    /** The chunk this item refers to. */
    var chunk: MetaDataChunk = _

    /** Reference to the previous item in the list. */
    var previous: ChunkItem = _

    /** Reference to the next item in the list. */
    var next: ChunkItem = _

    /**
      * Returns the size of the chunk this item refers to.
      *
      * @return the size of the referenced chunk
      */
    def size: Int = chunk.data.size
  }

  /**
    * Creates a ''ChunkItem'' that points to the undefined chunk.
    *
    * @return the undefined chunk item
    */
  private def undefinedChunkItem(): ChunkItem = {
    val item = new ChunkItem
    item.chunk = UndefinedChunk
    item
  }
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

  /** A map with the already received meta data chunks per medium. */
  private var receivedChunks = Map.empty[MediumID, ChunkItem]

  /** A map storing registration IDs for the requested media. */
  private var registrationIDs = Map.empty[MediumID, Int]

  /**
    * Start of the list with meta data chunks. Chunks at the beginning have
    * been accessed recently.
    */
  private var first: ChunkItem = _

  /**
    * End of the list with meta data chunks. Chunks at the end have not been
    * accessed for a while; they are removed when the cache runs out of space.
    */
  private var last: ChunkItem = _

  /** Keeps track about the current number of items in the cache. */
  private var currentCacheSize = 0

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
  def numberOfEntries: Int = currentCacheSize

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
    val chunkItem = receivedChunks.getOrElse(registration.mediumID,
      undefinedChunkItem())
    val currentChunk = chunkItem.chunk
    if (!currentChunk.complete) {
      addConsumer(registration, registration.mediumID)
      if (currentChunk eq UndefinedChunk) {
        val regID = mediaFacade.queryMetaDataAndRegisterListener(registration.mediumID)
        registrationIDs += registration.mediumID -> regID
      } else {
        moveToFront(chunkItem)
      }
    } else {
      moveToFront(chunkItem)
    }
    if (currentChunk.data.nonEmpty) {
      registration.callback(currentChunk)
    }
  }

  /**
    * Handles a request to remove a meta data listener for a medium. If there
    * are no more remaining listeners for this medium, the face can be notified
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
      receivedChunks.get(mediumID) foreach removeChunk
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
      receivedChunks.get(chunk.mediumID) match {
        case None =>
          val item = addChunk(chunk)
          receivedChunks = receivedChunks + (chunk.mediumID -> item)
          log.info("Added medium {} to cache.", chunk.mediumID)

        case Some(item) =>
          val oldChunkSize = item.size
          item.chunk = combine(item.chunk, chunk)
          currentCacheSize += item.size - oldChunkSize
      }
      handleCacheOverflow()
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
    * Adds the given meta data chunk to the beginning of the chunk list.
    *
    * @param chunk the affected chunk
    * @return the new chunk item
    */
  private def addChunk(chunk: MetaDataChunk): ChunkItem = {
    val item = new ChunkItem
    item.chunk = chunk
    item.next = first
    if (first != null) {
      first.previous = item
    }
    first = item
    if (last == null) last = item
    currentCacheSize += chunk.data.size
    item
  }

  /**
    * Moves the specified chunk item to the beginning of the LRU list.
    *
    * @param chunkItem the chunk item
    */
  private def moveToFront(chunkItem: ChunkItem): Unit = {
    if (chunkItem.previous != null) {
      removeFromList(chunkItem)
      chunkItem.next = first
      first.previous = chunkItem
      chunkItem.previous = null
      first = chunkItem
    }
  }

  /**
    * Removes the specified chunk from the LRU list and the cache.
    *
    * @param chunkItem the chunk item
    */
  private def removeChunk(chunkItem: ChunkItem): Unit = {
    removeFromList(chunkItem)
    receivedChunks -= chunkItem.chunk.mediumID
    currentCacheSize -= chunkItem.size
  }

  /**
    * Removes the specified chunk item from the LRU list.
    *
    * @param chunkItem the chunk item
    */
  private def removeFromList(chunkItem: ChunkItem): Unit = {
    if (last == chunkItem) {
      last = chunkItem.previous
    } else {
      chunkItem.next.previous = chunkItem.previous
    }

    if (first == chunkItem) {
      first = chunkItem.next
    } else {
      chunkItem.previous.next = chunkItem.next
    }
  }

  /**
    * Checks whether the cache has exceeded its limit. If so, media that were
    * not accessed recently are removed.
    */
  private def handleCacheOverflow(): Unit = {
    @tailrec def removeChunks(pos: ChunkItem): Unit = {
      log.debug("Current cache size: {}.", currentCacheSize)
      if (currentCacheSize > cacheSize && (pos ne first)) {
        if (consumerList(pos.chunk.mediumID).isEmpty) {
          log.info("Removed medium {} from cache.", last.chunk.mediumID)
          removeChunk(pos)
        }
        removeChunks(pos.previous)
      }
    }

    removeChunks(last)
  }
}
