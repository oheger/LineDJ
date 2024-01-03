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

package de.oliver_heger.linedj.platform.audio.model

import de.oliver_heger.linedj.shared.archive.media.MediaFileID

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
trait UnknownPropertyResolver:
  /**
    * Resolves the title from the given song ID. This implementation tries to
    * resolve the title from the file name of the song's URI.
    *
    * @param songID the ID of the song in question
    * @return the title for this song
    */
  def resolveTitle(songID: MediaFileID): String = generateTitle(songID)

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
    * Returns a list with ''SongTitleProcessor'' objects that are applied on a
    * generated song title. Per default, a song title is resolved by the song's
    * URI. Then all processors in the list returned by this method are
    * invoked in order to come to the final title. The default implementation
    * returns an empty list. This can be overridden to allow a fine-grained and
    * flexible song title generation.
    *
    * @return a list with ''SongTitleProcessor'' objects
    */
  def titleProcessors: List[SongTitleProcessor] = Nil

  /**
    * Generates the song title from the given ID. This method starts with the
    * song's URI and applies all [[SongTitleProcessor]] objects defined one by
    * one.
    *
    * @param songID the ID of the song in question
    * @return the generated title for this song
    */
  protected def generateTitle(songID: MediaFileID): String =
    titleProcessors.foldLeft(songID.uri) { (title, proc) =>
      proc.processTitle(title)
    }
