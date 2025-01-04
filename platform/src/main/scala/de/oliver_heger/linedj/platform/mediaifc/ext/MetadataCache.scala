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

import de.oliver_heger.linedj.platform.bus.ComponentID
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.{ConsumerFunction, ConsumerRegistration}
import de.oliver_heger.linedj.platform.mediaifc.MediaFacade
import de.oliver_heger.linedj.platform.mediaifc.ext.MetadataCache.MediumContent
import de.oliver_heger.linedj.shared.archive.media.{AvailableMedia, MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.{MediaMetadata, MetadataChunk, MetadataResponse}
import de.oliver_heger.linedj.utils.LRUCache
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive

object MetadataCache:
  /**
    * A data class representing the content of a medium as served by this
    * cache.
    *
    * An instance stores information about the files contained on a medium and
    * their metadata. It is closely related to the [[MetadataChunk]] class;
    * but while the latter is optimized for transporting data from the archive
    * to client components, this class allows easier access to single files by
    * storing their [[MediaFileID]]. (This is necessary for files stemming from
    * different media, as is the case for the synthetic global undefined
    * medium.) Note that these IDs do not contain a checksum, since checksums
    * for media are not available to the ''MetaDataCache''.
    *
    * @param data     a map with data about the files on this medium
    * @param complete a flag whether the content has been loaded completely
    */
  case class MediumContent(data: Map[MediaFileID, MediaMetadata],
                           complete: Boolean):
    /**
      * Returns a new instance that contains the data of this instance plus the
      * data of the given ''MetaDataChunk''. The ''complete'' flag from the
      * chunk is evaluated as well.
      *
      * @param chunk the ''MetaDataChunk'' to be added
      * @return the updated ''MediumContent''
      */
    def addChunk(chunk: MetadataChunk): MediumContent =
      val chunkData = chunk.data map { e =>
        (MediaFileID(chunk.mediumID, e._1), e._2)
      }
      MediumContent(data ++ chunkData, chunk.complete)

    /**
      * Returns a new instance with a map of data whose [[MediaFileID]]s have
      * checksums resolved from the ''AvailableMedia'' instance if possible.
      * Using this function, full IDs can be generated if the list of available
      * media is known.
      *
      * @param availableMedia the ''AvailableMedia'' instance
      * @return an updated ''MediumContent'' with checksums in the IDs
      */
    def resolveChecksums(availableMedia: AvailableMedia): MediumContent =
      copy(data = data map { e =>
        val id = availableMedia.media.get(e._1.mediumID).map(info => e._1.copy(checksum = Some(info.checksum)))
          .getOrElse(e._1)
        if id != e._1 then (id, e._2) else e
      })

  /**
    * A message class processed by [[MetadataCache]] to add a registration for the
    * metadata of a medium.
    *
    * With this message a component indicates its interest on the metadata of the
    * specified medium. Whenever metadata becomes available it is passed to the
    * callback function provided in the message.
    *
    * @param mediumID the ID of the medium
    * @param id       a unique ID to identify this listener; this can later be
    *                 used to remove the registration again
    * @param callback the callback function
    */
  case class MetadataRegistration(mediumID: MediumID, override val id: ComponentID,
                                  override val callback: ConsumerFunction[MediumContent])
    extends ConsumerRegistration[MediumContent]:
    override def unRegistration: AnyRef = RemoveMetadataRegistration(mediumID, id)

  /**
    * A message class processed by [[MetadataCache]] to remove the registration
    * for the metadata of a medium.
    *
    * When receiving a message of this type the cache will remove the represented
    * listener registration. This means that this listener will receive no further
    * messages for this medium.
    *
    * @param mediumID   the ID of the medium
    * @param listenerID the unique listener ID
    */
  case class RemoveMetadataRegistration(mediumID: MediumID, listenerID: ComponentID)

  /**
    * Constant for an empty ''MediumContent'' instance. Starting from this
    * instance, content objects can be constructed by adding chunks of 
    * metadata.
    */
  final val EmptyContent: MediumContent = MediumContent(Map.empty, complete = false)

  /**
    * The function that determines the size of items in the metadata LRU
    * cache.
    *
    * @param content the content in question
    * @return the size of this item in the cache
    */
  private def cacheSizeFunc(content: MediumContent): Int = content.data.size

/**
  * A specialized media interface extension implementing a client-side cache for
  * metadata.
  *
  * Clients may request metadata for one and the same medium multiple times; it
  * therefore makes sense to cache it locally. This is done by this class. It
  * stores chunks of metadata received from the archive and is also able to
  * combine them; this is done in form of [[MediumContent]] objects.
  *
  * This class is not directly accessed by other classes. Rather, interaction
  * takes place via the message bus: A component needing access to the metadata
  * of a medium sends a
  * [[de.oliver_heger.linedj.platform.mediaifc.ext.MetadataCache.MetadataRegistration]]
  * message on the message bus. The
  * message contains a callback through which the sender can be notified about
  * incoming metadata. If data for this medium is contained in the cache, the
  * callback is directly triggered. Whether the caller is registered depends on
  * the availability of metadata: if the whole medium is contained in the
  * cache, no registration is needed as all information has already been passed
  * via the callback. Otherwise, the caller is registered and receives
  * notifications about incoming metadata chunks. When all chunks for this
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
  * maximum number of metadata items to be stored can be specified. Note,
  * however, that this is not a hard limit, but works with the following
  * restrictions:
  *
  *  - at least a full medium is stored in the cache. So if this medium has
  *    more songs than the maximum size parameter, this limit is ignored.
  *  - The items of requested media remain in the cache until the medium has
  *    been completely loaded. So if clients request many media in parallel, the
  *    cache's size can (temporarily) grow over the specified limit.
  *
  * The size restriction works on medium level: When new metadata is added to
  * the cache and the cache size grows over the limit, the medium with the
  * oldest access time is searched and removed. (The cache operates in a LRU
  * mode.) This is repeated until the size limit can be met or no suitable
  * medium to be removed (according to the mentioned restrictions) can be
  * found.
  *
  * @param mediaFacade the facade to the media archive
  * @param cacheSize   the size restriction for this cache (see class
  *                    documentation for more information)
  */
class MetadataCache(val mediaFacade: MediaFacade, val cacheSize: Int)
  extends MediaIfcExtension[MediumContent, MediumID]:

  import MetadataCache._

  /** Logger. */
  private val log = LogManager.getLogger(getClass)

  /** The cache with the already received content objects per medium. */
  private var contentCache = createMetadataCache()

  /** A map storing the requested media for registration IDs. */
  private var registrationIDs = Map.empty[Int, MediumID]

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
  def numberOfEntries: Int = contentCache.size

  override protected def receiveSpecific: Receive =
    case registration: MetadataRegistration =>
      handleRegistration(registration)

    case MetadataResponse(chunk, regID) =>
      metadataReceived(chunk, regID)

    case RemoveMetadataRegistration(mediumID, listenerID) =>
      handleUnRegistration(mediumID, listenerID)

    case MediaFacade.MediaArchiveUnavailable =>
      clearConsumers()

  /**
    * Handles the registration of a metadata listener. If necessary, the
    * medium is requested from the archive. Access to this medium moves it to
    * the front of the metadata cache; so it will not be directly removed if
    * the cache becomes too large.
    *
    * @param registration the registration to be handled
    */
  private def handleRegistration(registration: MetadataRegistration): Unit =
    val currentContent = contentCache.getOrElse(registration.mediumID, EmptyContent)
    if !currentContent.complete then
      addConsumer(registration, registration.mediumID)
      if currentContent eq EmptyContent then
        val regID = mediaFacade.queryMetadataAndRegisterListener(registration.mediumID)
        registrationIDs += regID -> registration.mediumID
    if currentContent.data.nonEmpty then
      registration.callback(currentContent)

  /**
    * Handles a request to remove a metadata listener for a medium. If there
    * are no more remaining listeners for this medium, the facade can be notified
    * to stop tracking it for this client. If the medium ID or the listener ID
    * cannot be resolved, this operation has no effect.
    *
    * @param mediumID   the ID of the medium
    * @param listenerID the ID of the listener
    */
  private def handleUnRegistration(mediumID: MediumID, listenerID: ComponentID): Unit =
    val oldConsumers = consumerList(mediumID)
    removeConsumer(listenerID, mediumID)
    if consumerList(mediumID).isEmpty && oldConsumers.nonEmpty then
      mediaFacade.removeMetadataListener(mediumID)
      registrationIDs = registrationIDs.filterNot(_._2 == mediumID)
      contentCache removeItem mediumID

  /**
    * Processes metadata that was received from the archive. The new chunk of
    * data is combined with data already stored in the cache. Listeners
    * interested in the affected medium are notified. If the cache becomes too
    * large, media which have not been accessed recently are removed.
    *
    * @param chunk the new chunk of data
    * @param regID the registration ID
    */
  private def metadataReceived(chunk: MetadataChunk, regID: Int): Unit =
    registrationIDs.get(regID) foreach { mediumID =>
      lazy val contentUpdate = EmptyContent.addChunk(chunk)
      if contentCache contains mediumID then
        contentCache.updateItem(mediumID)(content => content.addChunk(chunk))
      else
        contentCache.addItem(mediumID, contentUpdate)
        log.info("Added medium {} to cache.", mediumID)

      invokeConsumers(contentUpdate, mediumID)
      if chunk.complete then
        removeConsumers(chunk.mediumID)
        registrationIDs -= regID
    }

  /**
    * @inheritdoc This implementation removes the cache as it might contain
    *             stale data.
    */
  override def onArchiveAvailable(hasConsumers: Boolean): Unit =
    contentCache = createMetadataCache()

  /**
    * A function that determines whether an item from the metadata cache can
    * be removed. Only items can be removed, which have been completely
    * downloaded.
    *
    * @param content the content item in question
    * @return a flag whether this item can be removed from the cache
    */
  private def cacheRemovableFunc(content: MediumContent): Boolean =
    if content.complete then
      log.info("Removing medium {} from metadata cache.", content.data.iterator.next()._1.mediumID)
    content.complete

  /**
    * Creates the metadata cache.
    *
    * @return the new cache
    */
  private def createMetadataCache(): LRUCache[MediumID, MediumContent] =
    new LRUCache[MediumID, MediumContent](cacheSize)(sizeFunc = cacheSizeFunc, removableFunc = cacheRemovableFunc)
