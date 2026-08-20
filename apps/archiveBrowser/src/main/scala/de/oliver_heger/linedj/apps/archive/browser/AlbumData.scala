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

package de.oliver_heger.linedj.apps.archive.browser

import de.oliver_heger.linedj.archive.server.model.ArchiveModel

private object AlbumData:
  /** Constant for a string to be used for an undefined property. */
  private val UndefinedProperty = ""
end AlbumData

/**
  * A data class to be used in the table model to display albums.
  *
  * An instance wraps an [[ArchiveModel.AlbumInfo]] object that holds the
  * actual information about the album. The relevant properties are exposed
  * via properties following the Java beans specification.
  *
  * @param albumInfo  information about this album
  * @param artistName the name of the artist who produced this album
  */
private case class AlbumData(albumInfo: ArchiveModel.AlbumInfo,
                             artistName: String):
  /**
    * Returns the title of this album.
    *
    * @return the album title
    */
  def getTitle: String = albumInfo.albumName

  /**
    * Returns the name of the artist who produced this album. If the artist
    * name is undefined, an empty string is returned.
    *
    * @return the artist name
    */
  def getArtist: String = artistName
