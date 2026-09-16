/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.reorder.spi

import scala.collection.immutable.Seq

/**
  * A base trait for reorder service implementation which produce a random
  * order based on some property of the passed in songs.
  *
  * This trait provides a fully functional implementation of the _reorder()_
  * method which does the following: A grouping is created on the input
  * sequence of songs. Then the different groups are brought in a random
  * order, and the songs of each group are ordered by a specific ordering.
  * Finally, the ordered songs of all groups are concatenated to the resulting
  * sequence.
  *
  * Concrete implementations have to provide a grouping function and the
  * ordering for sorting the songs in a group.
  *
  * @tparam G the type to be used for the grouping of songs
  */
trait RandomGroupingPlaylistReorderer[G] extends LocalizedPlaylistReorderer:
  override def reorder[A](songs: Seq[A], extractor: MetadataExtractor[A]): Seq[A] =
    val grouping = songs.groupBy(groupSong(extractor))
    util.Random.shuffle(grouping.toList).flatMap(_._2.sorted(groupOrdering(extractor)))

  /**
    * Implements the grouping function. This function is used to group the
    * songs based on a specific property.
    *
    * @param extractor the [[MetadataExtractor]] for the song type
    * @param song      the song to be grouped
    * @tparam A the type of the song representation
    * @return the group for this song
    */
  def groupSong[A](extractor: MetadataExtractor[A])(song: A): G

  /**
    * Returns the ordering to be used for the songs in a group. This ordering
    * is applied to all groups.
    *
    * @param extractor the [[MetadataExtractor]] for the song type
    * @tparam A the type of the song representation
    * @return the ordering for the songs in a group
    */
  def groupOrdering[A](extractor: MetadataExtractor[A]): Ordering[A]
