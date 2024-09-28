/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.utils

import de.oliver_heger.linedj.utils.LRUCache.CacheItem

import scala.annotation.tailrec

object LRUCache:
  /**
    * A default size function which counts each entry of the cache as 1.
    * This is appropriate if the cache is limited to a specific number of
    * elements. In cases where container elements are stored that can contain
    * an arbitrary number of elements, an alternative size function should
    * probably be used.
    *
    * @tparam B the value type of the cache
    * @return the standard size function for single elements
    */
  def SingleSize[B]: B => Int = _ => 1

  /**
    * A default removable function which states that all elements in the cache
    * can be removed.
    *
    * @tparam B the value type of the cache
    * @return the standard removable function which allows all removals
    */
  def AllRemovable[B]: B => Boolean = _ => true

  /**
    * Internally used class to construct a double-linked list of items stored
    * in the cache. This is needed to implement LRU functionality.
    *
    * @param key the key of this item
    * @tparam K the type of the key
    * @tparam V the type of the value
    */
  private case class CacheItem[K, V](key: K):
    /** The value of this item. */
    var value: V = _

    /** Reference to the previous item in the list. */
    var previous: CacheItem[K, V] = _

    /** Reference to the next item in the list. */
    var next: CacheItem[K, V] = _


/**
  * A class implementing simple LRU cache functionality.
  *
  * For some use cases, it is necessary to cache data loaded from a media
  * archive in a client application. For instance, an archive browser may
  * display metadata for audio files, but it is not desirable that the
  * metadata of the whole archive gets eventually downloaded to the client.
  *
  * With this class it is possible to limit the amount of data available on the
  * client. Data which is accessed more frequently remains in the cache, while
  * other entries are removed if space becomes rare.
  *
  * An instance can be configured with some parameters: The most important one
  * is the maximum cache size. If this size is reached, entries are removed
  * from the cache until it gets again below this threshold. (However, the
  * cache will keep a single entry; so if this entry is bigger than the
  * configured size, the cache can actually go out of its limits.)
  *
  * Per default, an entry in the cache is considered to have a size of 1. If
  * the cache stores container objects, it is possible to specify a
  * ''size function''. This function is invoked to determine the actual size
  * of an entry.
  *
  * It is also possible to provide a ''removableFunc''. This function is called
  * when items are to be removed from the cache because its capacity limit has
  * been exceeded. Only items are removed from the cache for which this
  * function returns '''true'''.
  *
  * Note that this class is not a general-purpose LRU cache implementation; it
  * is rather designed to handle the use cases described in a pretty
  * straight-forward way. Therefore, its functionality is limited to the most
  * essential operations. It is also not thread-safe.
  *
  * @param cacheSize     the size threshold of this cache
  * @param sizeFunc      function to determine the size of an entry
  * @param removableFunc function to determine removable entries
  * @tparam A the type of the keys of the cache
  * @tparam B the type of the values of the cache
  */
class LRUCache[A, B](cacheSize: Int)(sizeFunc: B => Int = LRUCache.SingleSize,
                                     removableFunc: B => Boolean = LRUCache.AllRemovable):
  /** A map for storing the items contained in the cache. */
  private var items = Map.empty[A, CacheItem[A, B]]

  /**
    * Start of the list with cache items. Items at the beginning have
    * been accessed recently.
    */
  private var first: CacheItem[A, B] = _

  /**
    * End of the list with cache items. Items at the end have not been
    * accessed for a while; they are removed when the cache runs out of space.
    */
  private var last: CacheItem[A, B] = _

  /** Keeps track about the current size of the cache. */
  private var currentCacheSize = 0

  /**
    * Queries the item with the specified key and returns an ''Option'' with
    * the result. If the key is contained in the cache, this operation is
    * considered an access to it; therefore, the key is moved to the front and
    * will not be removed directly when the capacity limit is reached.
    *
    * @param k the key
    * @return an ''Option'' with the value of this key
    */
  def get(k: A): Option[B] =
    items get k match
      case Some(item) =>
        moveToFront(item)
        Some(item.value)
      case None => None

  /**
    * Obtains the item with the given key from the cache or returns the
    * alternative if the key is not contained. If the key is found, this
    * operation is considered an access, and the key is moved to the front of
    * the LRU list, so that it will not be removed directly when the capacity
    * limit is reached.
    *
    * @param k   the key
    * @param alt the alternative value if the key is not found
    * @return the value from the cache or the alternative
    */
  def getOrElse(k: A, alt: => B): B =
    get(k) getOrElse alt

  /**
    * Checks whether the specified key is contained in this cache. This does
    * not affect the order in the LRU list.
    *
    * @param k the key to be checked
    * @return a flag whether this key is contained in this cache
    */
  def contains(k: A): Boolean = items contains k

  /**
    * Returns a set with all keys that are currently contained in this cache.
    *
    * @return the key set of this cache
    */
  def keySet: Set[A] = items.keySet

  /**
    * Returns a map with the current content of this cache.
    *
    * @return a map view of this cache
    */
  def toMap: Map[A, B] = items.map(e => e._1 -> e._2.value)

  /**
    * Adds the specified key-value pair to the cache. This method assumes that
    * the key is not yet contained in the cache. Callers are responsible to
    * ensure this; otherwise, the cache may become inconsistent. The key is
    * added to the front of the LRU list, so it will not be removed directly
    * when the capacity limit is reached.
    *
    * @param k the key
    * @param v the value
    */
  def addItem(k: A, v: B): Unit =
    val item = addToLRUList(k, v)
    items += k -> item
    handleCacheOverflow()

  /**
    * Performs an update of a key if it is contained in the cache. With this
    * method the value of a key can be updated without changing its position
    * in the LRU list. If the key is contained in the cache, the update
    * function is invoked. Otherwise, this call has no effect, and result is
    * '''false'''.
    *
    * @param k the key in question
    * @param f a function that performs the update
    * @return a flag whether the update was done
    */
  def updateItem(k: A)(f: B => B): Boolean =
    items get k match
      case Some(item) =>
        val oldVal = item.value
        item.value = f(item.value)
        currentCacheSize += sizeFunc(item.value) - sizeFunc(oldVal)
        handleCacheOverflow()
        true
      case None =>
        false

  /**
    * Removes the item with the specified key from this cache. The old value
    * is returned (''None'' if the key was not contained in this cache).
    *
    * @param k the key of the item to be removed
    * @return an ''Option'' with the last value of this key
    */
  def removeItem(k: A): Option[B] =
    items get k match
      case Some(item) =>
        removeCacheItem(item)
        Some(item.value)
      case None => None

  /**
    * Returns the current size of the cache.
    *
    * @return the cache size
    */
  def size: Int = currentCacheSize

  /**
    * Adds an item for the given value to the beginning of the item list.
    *
    * @param key   the key
    * @param value the value to be added
    * @return the new cache item
    */
  private def addToLRUList(key: A, value: B): CacheItem[A, B] =
    val item = CacheItem[A, B](key)
    item.value = value
    item.next = first
    if first != null then
      first.previous = item
    first = item
    if last == null then last = item
    currentCacheSize += sizeFunc(value)
    item

  /**
    * Moves the specified cache item to the beginning of the LRU list.
    *
    * @param cacheItem the chunk item
    */
  private def moveToFront(cacheItem: CacheItem[A, B]): Unit =
    if cacheItem.previous != null then
      removeFromList(cacheItem)
      cacheItem.next = first
      first.previous = cacheItem
      cacheItem.previous = null
      first = cacheItem

  /**
    * Removes the specified item from the LRU list and the cache.
    *
    * @param cacheItem the item
    */
  private def removeCacheItem(cacheItem: CacheItem[A, B]): Unit =
    removeFromList(cacheItem)
    items -= cacheItem.key
    currentCacheSize -= sizeFunc(cacheItem.value)

  /**
    * Removes the specified cache item from the LRU list.
    *
    * @param cacheItem the cache item
    */
  private def removeFromList(cacheItem: CacheItem[A, B]): Unit =
    if last == cacheItem then
      last = cacheItem.previous
    else
      cacheItem.next.previous = cacheItem.previous

    if first == cacheItem then
      first = cacheItem.next
    else
      cacheItem.previous.next = cacheItem.next

  /**
    * Checks whether the cache has exceeded its limit. If so, items that were
    * not accessed recently are removed.
    */
  private def handleCacheOverflow(): Unit =
    @tailrec def removeItems(pos: CacheItem[A, B]): Unit =
      if currentCacheSize > cacheSize && (pos ne first) then
        if removableFunc(pos.value) then
          removeCacheItem(pos)
        removeItems(pos.previous)

    removeItems(last)
