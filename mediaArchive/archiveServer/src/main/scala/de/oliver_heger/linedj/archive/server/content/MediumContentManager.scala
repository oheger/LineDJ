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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.content.MediumContentManager.{DataExtractor, KeyExtractor, idFor}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata

import java.util.Locale

private object MediumContentManager:
  /**
    * Alias for a function to extract a key from a given [[MediaMetadata]]
    * object. This key is used for grouping the data, for instance, all songs
    * from an artist.
    */
  type KeyExtractor = MediaMetadata => Option[String]

  /**
    * Alias for a function to extract the data to be managed from a
    * [[MediaMetadata]] object.
    */
  type DataExtractor[DATA] = MediaMetadata => DATA

  /**
    * Generates a string-based ID from the given result of a [[KeyExtractor]]
    * function. Such IDs can then be used to query the data associated with
    * this ID. The main purpose of such IDs is to have a well-defined and
    * unique way to query data that also handles the case that no key could be
    * extracted (i.e. a ''None'' value is passed in).
    *
    * @param optKey   the optional key extracted from [[MediaMetadata]]
    * @param idPrefix the prefix to generate IDs
    * @return the ID for the passed in key
    */
  def idFor(optKey: Option[String], idPrefix: String): String =
    idPrefix + (optKey match
      case Some(value) => "_" + value.toLowerCase(Locale.ROOT).replace(' ', '_')
      case None => "0"
      )
end MediumContentManager

/**
  * An internally used helper class to manage data extracted from a collection
  * of [[MediaMetadata]] objects.
  *
  * A medium can contain songs from different artists with different albums.
  * Users may want to navigate through these songs based on different criteria,
  * for instance browse the songs of a specific artist or the songs contained
  * on a specific album, or view the albums of a specific artist. For each of
  * such views, a specific content manager instance can be created. It is
  * configured with functions to extract the desired data from song metadata.
  * It then groups the data based on extracted keys like artist IDs of album
  * IDs. While doing the grouping, it handles corner cases like undefined key
  * (for instance missing information in a song) gracefully. It exposes the
  * resulting mapping to be queried.
  *
  * Queries can already be served while reading the archive's content is still
  * in progress. When no song information arrives, the mapping becomes invalid
  * and needs to be reset. It is then re-constructed when the next query comes
  * in. Because of the state managed for this purpose, instances are not
  * thread-safe and need to be guarded.
  *
  * @param keyExtractor  the function to extract keys; the keys are the basis
  *                      for grouping the data
  * @param dataExtractor the function to extract data
  * @param idPrefix      a prefix to generate IDs for extracted keys
  * @param ord           a [[Ordering]] for sorting the data
  * @tparam DATA the type of the data managed by this instance
  */
private class MediumContentManager[DATA](keyExtractor: KeyExtractor,
                                         dataExtractor: DataExtractor[DATA],
                                         idPrefix: String)
                                        (using ord: Ordering[DATA]):
  /** Stores the current list with song metadata. */
  private var metadata: Iterable[MediaMetadata] = List.empty

  /** The ID -> key mapping if defined. */
  private var idMapping: Option[Map[String, String]] = None

  /** The data mapping if defined. */
  private var dataMapping: Option[Map[String, List[DATA]]] = None

  /**
    * Notifies this object that new data is available. The updated data is
    * passed in. This causes internal mappings to be reset, so that they are
    * recreated on next access.
    *
    * @param data the updated collection of song metadata
    */
  def update(data: Iterable[MediaMetadata]): Unit =
    if data != metadata then
      metadata = data
      idMapping = None
      dataMapping = None

  /**
    * Returns the data mapped to the key with the given ID. If no up-to-date
    * mapping is available, it is constructed now. If the ID does not match an
    * existing key, result is ''None''.
    *
    * @param id the ID of the key for which to retrieve data
    * @return an [[Option]] with the data assigned to this key
    */
  def apply(id: String): Option[List[DATA]] =
    getMappings._1.get(id)

  /**
    * Returns a mapping with generated IDs to keys. The keys returned by the
    * extractor function are not used directly, but they are transformed to IDs
    * to drop whitespace and ignore case. This mapping allows figuring out the
    * IDs for the extracted keys.
    *
    * @return a map assigning generated IDs to extracted keys
    */
  def keyMapping: Map[String, String] =
    getMappings._2

  /**
    * Returns the up-to-date mappings. If necessary, they are constructed now
    * and cached.
    *
    * @return the current mappings
    */
  private def getMappings: (Map[String, List[DATA]], Map[String, String]) =
    (for
      d <- dataMapping
      i <- idMapping
    yield (d, i)) match
      case Some(mappings) => mappings
      case None =>
        val newMappings = constructMappings()
        dataMapping = Some(newMappings._1)
        idMapping = Some(newMappings._2)
        newMappings

  /**
    * Creates the mappings managed by this instance based on the current list
    * of metadata.
    *
    * @return a tuple with the newly constructed mappings
    */
  private def constructMappings(): (Map[String, List[DATA]], Map[String, String]) =
    val groupedMetadata = metadata.toList.groupBy(m => idFor(keyExtractor(m), idPrefix))
    val data = groupedMetadata.map(e => (e._1, e._2.map(dataExtractor).sorted))
    val ids = groupedMetadata.map(e => e._1 -> keyExtractor(e._2.head).getOrElse(""))
    (data, ids)
