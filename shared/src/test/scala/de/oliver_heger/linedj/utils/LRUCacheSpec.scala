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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Seq

object LRUCacheSpec:
  /**
    * Generates a test key based on the given index.
    *
    * @param idx the index
    * @return the test key with this index
    */
  private def entryKey(idx: Int): String = "k" + idx

  /**
    * Generates a test cache entry based on the given index.
    *
    * @param idx the index
    * @return the key-value pair based on this index
    */
  private def entry(idx: Int): (String, Int) = (entryKey(idx), idx)

  /**
    * Generates a sequence of test cache entries with indices in the provided
    * range.
    *
    * @param from the from index
    * @param to   the to index (including)
    * @return the sequence of entries
    */
  private def entries(from: Int, to: Int): Seq[(String, Int)] =
    (from to to) map entry

  /**
    * Adds the specified entries to the given cache.
    *
    * @param cache the cache
    * @param pairs the entries to be added
    * @return the cache with entries added
    */
  private def addEntries(cache: LRUCache[String, Int], pairs: Seq[(String, Int)]):
  LRUCache[String, Int] =
    pairs foreach (p => cache.addItem(p._1, p._2))
    cache

/**
  * Test class for ''LRUCache''.
  */
class LRUCacheSpec extends AnyFlatSpec with Matchers:

  import LRUCacheSpec._

  /**
    * Checks that the given cache contains all the specified entries.
    *
    * @param cache the cache
    * @param pairs the entries to be checked
    */
  private def assertContains(cache: LRUCache[String, Int], pairs: (String, Int)*): Unit =
    pairs foreach { p =>
      cache contains p._1 shouldBe true
      cache get p._1 should be(Some(p._2))
    }

  "A LRUCache" should "allow adding entries up to its capacity" in:
    val pairs = entries(1, 10)

    val cache = addEntries(new LRUCache[String, Int](pairs.size)(), pairs)
    cache.size should be(pairs.size)
    assertContains(cache, pairs: _*)
    cache.keySet should contain theSameElementsAs pairs.map(_._1)
    cache.toMap should contain theSameElementsAs pairs

  it should "remove older entries to keep its maximum capacity" in:
    val pairs = entries(1, 8)

    val cache = addEntries(new LRUCache[String, Int](pairs.size - 1)(), pairs)
    cache.size should be(pairs.size - 1)
    cache contains pairs.head._1 shouldBe false
    cache get pairs.head._1 shouldBe empty
    assertContains(cache, pairs.tail: _*)
    cache.keySet should contain theSameElementsAs pairs.tail.map(_._1)
    cache.toMap should contain theSameElementsAs pairs.tail

  it should "move an entry to the front when it is accessed" in:
    val pairs = entries(1, 8)
    val cache = addEntries(new LRUCache[String, Int](pairs.size)(), pairs)

    cache get pairs.head._1 should be(Some(pairs.head._2))
    cache.addItem(entryKey(42), 42)
    cache get pairs.head._1 should be(Some(pairs.head._2))
    cache contains pairs.drop(1).head._1 shouldBe false

  it should "allow updating an entry" in:
    val pairs = entries(1, 4)
    val modIdx = 2
    val modEntry = entryKey(modIdx)
    val cache = addEntries(new LRUCache[String, Int](pairs.size)(), pairs)

    cache.updateItem(modEntry)(_ + 1) shouldBe true
    cache.get(modEntry).get should be(modIdx + 1)

  it should "not change LRU order by an update" in:
    val pairs = entries(1, 16)
    val cache = addEntries(new LRUCache[String, Int](pairs.size)(), pairs)

    cache.updateItem(pairs.head._1)(_ => 42)
    cache.addItem(entryKey(100), 200)
    cache contains pairs.head._1 shouldBe false

  it should "handle an update operation for a non-existing key" in:
    val pairs = entries(1, 16)
    val cache = addEntries(new LRUCache[String, Int](pairs.size)(), pairs)

    cache.updateItem("non existing key")(_ => 42) shouldBe false
    assertContains(cache, pairs: _*)

  it should "support a get with an alternative" in:
    val alt = 42
    val cache = new LRUCache[String, Int](10)()

    cache.getOrElse("foo", alt) should be(alt)

  it should "support an alternative size function" in:
    val pairs = entries(1, 7)
    val cache = new LRUCache[String, Int](16)(sizeFunc = i => i)

    addEntries(cache, pairs)
    cache.size should be(13)
    assertContains(cache, entry(6), entry(7))

  it should "correctly update the size during an update operation" in:
    val e = entry(1)
    val cache = new LRUCache[String, Int](10)(sizeFunc = i => i)
    addEntries(cache, List(e))

    val newSize = 5
    cache.updateItem(e._1)(_ => newSize)
    cache.size should be(newSize)

  it should "respect the maximum capacity during update operations" in:
    val pairs = entries(1, 7)
    val cache = new LRUCache[String, Int](21)(sizeFunc = i => i)
    addEntries(cache, pairs)

    cache.updateItem(entryKey(7))(_ => 10)
    cache.size should not be >(21)
    cache contains entryKey(1) shouldBe false

  it should "support an alternative remove function" in:
    val cache = new LRUCache[String, Int](10)(removableFunc = i => i != 4)

    addEntries(cache, entries(1, 10))
    addEntries(cache, entries(20, 30))
    assertContains(cache, entry(4))

  it should "allow removing an item from the cache" in:
    val removeIdx = 4
    val removeKey = entryKey(removeIdx)
    val cache = addEntries(new LRUCache[String, Int](32)(), entries(1, 8))

    cache removeItem removeKey should be(Some(removeIdx))
    cache.size should be(7)
    cache contains removeKey shouldBe false

  it should "handle a remove operation for a non-existing key" in:
    val cache = addEntries(new LRUCache[String, Int](8)(), entries(1, 2))

    cache removeItem entryKey(42) shouldBe empty
