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

/**
  * A module defining multiple frequently used [[Ordering]] implementations
  * that can be applied to the metadata properties of media files.
  *
  * The functions defined here can be used by concrete [[PlaylistReorderer]]
  * implementations to define the comparison logic. Note that they do not
  * operate on concrete data types, but make use of a [[MetadataExtractor]]
  * to obtain the relevant properties.
  */
object MediaFileOrderings:
  /**
    * An [[Ordering]] on strings that is case-insensitive. This is used for
    * most string comparisons, since here, case does not matter.
    */
  private val CaseInsensitiveOrdering = Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER)

  /**
    * Returns an [[Ordering]] for a specific data type that sorts instances by
    * their track number and then alphabetically by their titles.
    *
    * Files without a track number are sorted after files with a defined track
    * number.
    *
    * @param extractor the [[MetadataExtractor]] for this type
    * @tparam A the type of the elements
    * @return the [[Ordering]] on track number and title
    */
  def songTrackNoOrdering[A](extractor: MetadataExtractor[A]): Ordering[A] =
    Ordering.by[A, Int](s => extractor.trackNumber(s) getOrElse Int.MaxValue)
      .orElseBy[String](extractor.titleOrDefault)(using CaseInsensitiveOrdering)

  /**
    * Returns an [[Ordering]] for a specific data type that sorts instances by
    * the albums they belong to. It applies the following criteria:
    *
    * First the inception year of the album is evaluated. Older albums are
    * sorted before newer ones. Files without a defined inception year are
    * sorted after files with a defined inception year.
    *
    * Albums with the same inception year are sorted by their names in
    * alphabetic order (ignoring case).
    *
    * The files belonging to an album are sorted by
    * [[songTrackNoOrdering]].
    *
    * @param extractor the [[MetadataExtractor]] for this type
    * @tparam A the type of the elements
    * @return the [[Ordering]] on album year, album name, and track number
    */
  def songAlbumOrdering[A](extractor: MetadataExtractor[A]): Ordering[A] =
    Ordering.by[A, Int](s => extractor.inceptionYear(s) getOrElse Int.MaxValue)
      .orElseBy[String](extractor.albumOrDefault)(using CaseInsensitiveOrdering)
      .orElse(songTrackNoOrdering(extractor))

  /**
    * Returns an [[Ordering]] for a specific data type that sorts instances by
    * the artists they belong to. It sorts files primarily by the artist name
    * (ignoring case). Files belonging to the same artist are ordered by
    * [[songAlbumOrdering]].
    *
    * @param extractor the [[MetadataExtractor]] for this type
    * @tparam A the type of the elements
    * @return the [[Ordering]] on artist name, album, and track number
    */
  def songArtistOrdering[A](extractor: MetadataExtractor[A]): Ordering[A] =
    Ordering.by[A, String](extractor.artistOrDefault)(using CaseInsensitiveOrdering)
      .orElse(songAlbumOrdering(extractor))
