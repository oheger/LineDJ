/*
 * Copyright 2015 The Developers Team.
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

package de.oliver_heger.linedj.browser.model

import de.oliver_heger.linedj.metadata.MediaMetaData

object SongData {
  /** A separator character for URIs. */
  private val UriSeparator = '/'

  /** The backslash character. */
  private val BackSlash = '\\'

  /** Constant for the extension character. */
  private val Ext = '.'

  /**
   * Extracts the song title from the given URI.
   * @param uri the URI
   * @return the song title
   */
  private def extractTitle(uri: String): String = {
    val name = uri.replace(BackSlash, UriSeparator).split(UriSeparator).lastOption
    val nameWithoutExt = name map { n =>
      val extPos = n lastIndexOf Ext
      if (extPos > 0) n take extPos else n
    }
    nameWithoutExt.get
  }
}

/**
 * A simple data class that holds information about a song.
 *
 * The song is mainly described by the meta data available for it. In addition,
 * the unique URI is stored. As meta data is optional, the URI might be used as
 * fallback for the song title if no title is available.
 *
 * @param uri the URI of this song as obtained from the server
 * @param metaData meta data about this song
 */
case class SongData(uri: String, metaData: MediaMetaData) extends Ordered[SongData] {

  import SongData._

  /**
   * The title of the managed song. If this is specified by the meta
   * data, it is obtained from there. Otherwise, the last part of the URI is
   * returned.
   */
  lazy val getTitle: String = metaData.title getOrElse extractTitle(uri)

  /**
   * @inheritdoc This implementation sorts songs by track number in the first
   *             level. If this is the same, they are sorted by title.
   */
  override def compare(that: SongData): Int = {
    val c = extractTrackNumber(metaData) - extractTrackNumber(that.metaData)
    if (c != 0) c else getTitle compare that.getTitle
  }

  /**
   * Extracts the track number from the given meta data object. If it is
   * undefined, the maximum int value is returned.
   * @param meta the meta data
   * @return the track number
   */
  private def extractTrackNumber(meta: MediaMetaData): Int =
    meta.trackNumber getOrElse Integer.MAX_VALUE
}
