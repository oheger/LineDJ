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

object MetadataExtractor:
  /**
    * Constant for a value to display for undefined string metadata properties.
    */
  private val UndefinedString = ""

  /**
    * Constant for the inception year to use if the metadata property is
    * undefined.
    */
  private val UndefinedInceptionYear = 0

  /**
    * Constant for the track number to use if the metadata property is
    * undefined.
    */
  private val UndefinedTrackNo = -1
end MetadataExtractor

/**
  * Definition of an interface that allows querying metadata from an object of
  * a specific type.
  *
  * This trait provides an abstraction over the concrete representation of a
  * media file. It defines accessor methods for a number of properties that are
  * relevant for different reorder algorithms. Using this abstraction makes it
  * possible to apply the reorder components to all media file representations
  * for which a _MetadataExtractor_ implementation is available.
  *
  * @tparam A the type on which this extractor operates
  */
trait MetadataExtractor[A]:

  import MetadataExtractor.*

  /**
    * Extracts the title of the given data object.
    *
    * @param data the representation of the media file
    * @return an [[Option]] with the title of this media file
    */
  def title(data: A): Option[String]

  /**
    * Extracts the artist of the given data object.
    *
    * @param data the representation of the media file
    * @return an [[Option]] with the artist name
    */
  def artist(data: A): Option[String]

  /**
    * Extracts the album of the given data object.
    *
    * @param data the representation of the media file
    * @return an [[Option]] with the album name
    */
  def album(data: A): Option[String]

  /**
    * Extracts the inception year of the album of the given data object.
    *
    * @param data the representation of the media file
    * @return an [[Option]] with the inception year
    */
  def inceptionYear(data: A): Option[Int]

  /**
    * Extracts the track number of the given data object.
    *
    * @param data the representation of the media file
    * @return an [[Option]] with the track number
    */
  def trackNumber(data: A): Option[Int]

  /**
    * Extracts the URI (relative path) of the given data object on its
    * medium. This can be used to sort songs by their directory structure.
    *
    * @param data the representation of the media file
    * @return the URI of this file on its medium
    */
  def uri(data: A): String

  /**
    * Returns the value of the _title_ property of the given data object or the
    * default value if this property is undefined.
    *
    * @param data the representation of the media file
    * @return the title of this media file
    */
  def titleOrDefault(data: A): String = title(data).getOrElse(UndefinedString)

  /**
    * Returns the value of the _artist_ property of the given data object or the
    * default value if this property is undefined.
    *
    * @param data the representation of the media file
    * @return the artist name
    */
  def artistOrDefault(data: A): String = artist(data).getOrElse(UndefinedString)

  /**
    * Returns the value of the _album_ property of the given data object or the
    * default value if this property is undefined.
    *
    * @param data the representation of the media file
    * @return the album name
    */
  def albumOrDefault(data: A): String = album(data).getOrElse(UndefinedString)

  /**
    * Returns the value of the _inceptionYear_ property of the given data object
    * or the default value if this property is undefined.
    *
    * @param data the representation of the media file
    * @return the inception year
    */
  def inceptionYearOrDefault(data: A): Int = inceptionYear(data).getOrElse(UndefinedInceptionYear)

  /**
    * Returns the value of the _trackNumber_ property of the given data object
    * or the default value if this property is undefined.
    *
    * @param data the representation of the media file
    * @return the track number
    */
  def trackNumberOrDefault(data: A): Int = trackNumber(data).getOrElse(UndefinedTrackNo)
