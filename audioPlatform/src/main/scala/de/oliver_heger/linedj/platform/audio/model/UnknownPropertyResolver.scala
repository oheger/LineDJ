/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.model

import de.oliver_heger.linedj.shared.archive.media.MediaFileID

object UnknownPropertyResolver {
  /** A separator character for URIs. */
  private val UriSeparator = '/'

  /** The backslash character. */
  private val BackSlash = '\\'

  /** Constant for the extension character. */
  private val Ext = '.'
}

/**
  * A trait for resolving unknown meta data properties when creating
  * [[SongData]] instances.
  *
  * If meta data is incomplete, some default values have to be set for key
  * properties. Such values are typically application-specific. Therefore, this
  * trait is introduced, to allow an application to plug in an implementation
  * which fits its needs.
  *
  * The methods to resolve unknown meta data properties are passed the ID of
  * the song affected, which also contains the URL to the song file. This may
  * be used by an implementation to come to a result, e.g. by assuming a
  * certain structure in folder and file names.
  *
  * For the title there is already a default implementation which tries to
  * extract the song name from the URI.
  */
trait UnknownPropertyResolver {

  import UnknownPropertyResolver._

  /**
    * Resolves the title from the given song ID. This implementation tries to
    * resolve the title from the file name of the song's URI.
    *
    * @param songID the ID of the song in question
    * @return the title for this song
    */
  def resolveTitle(songID: MediaFileID): String = extractFileName(songID)

  /**
    * Resolves the name of a missing artist.
    *
    * @param songID the ID of the song in question
    * @return the artist name to be used for this song
    */
  def resolveArtistName(songID: MediaFileID): String

  /**
    * Resolves the name of a missing album.
    *
    * @param songID the ID of the song in question
    * @return the album name to be used for this song
    */
  def resolveAlbumName(songID: MediaFileID): String

  /**
    * Extracts the file name (without extension) from the song's URI. This can
    * be used to derive some information from it.
    *
    * @param songID the ID of the song in question
    * @return the file name from the song's URI
    */
  protected def extractFileName(songID: MediaFileID): String = {
    val name = songID.uri.replace(BackSlash, UriSeparator).split(UriSeparator).last
    val extPos = name lastIndexOf Ext
    if (extPos > 0) name take extPos else name
  }
}
