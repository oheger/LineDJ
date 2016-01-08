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

package de.oliver_heger.linedj.browser.media

/**
 * A simple data class which uniquely identifies an album.
 *
 * To be unique, the key consists of the album name and the artist name. Both
 * can be displayed to the end user. (This implies that unknown names - due to
 * missing meta data - have already been resolved.)
 *
 * @param artist the name of the artist
 *               @param album the name of the album
 */
private case class AlbumKey(artist: String, album: String)

/**
 * A data class which stores an album key and the inception year. This
 * information is required for sorting the albums of an artist correctly.
 * Albums whose inception year is unknown are sorted behind other albums.
 *
 * @param key the album key
 * @param year the inception year
 */
private case class AlbumKeyWithYear(key: AlbumKey, year: Int) extends Ordered[AlbumKeyWithYear] {
  /**
   * Orders albums by their inception year and name.
   */
  override def compare(that: AlbumKeyWithYear): Int = {
    val yearDiff = year - that.year
    if (yearDiff != 0) yearDiff
    else key.album.compare(that.key.album)
  }
}
