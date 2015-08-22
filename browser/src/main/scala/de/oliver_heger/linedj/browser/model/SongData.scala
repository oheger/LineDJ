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
  /** Constant for an unknown song duration. */
  val UnknownDuration = -1

  /** Constant for an unknown track number. */
  val UnknownTrackNumber = ""

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
 * This class is also used by model classes for UI controls displaying song
 * information. Therefore, it has to expose properties following the Java Beans
 * standard. Because these properties are directly displayed in the UI, there
 * are already some format conversions.
 *
 * @param uri the URI of this song as obtained from the server
 * @param metaData meta data about this song
 * @param resolver the resolver for unknown names
 */
case class SongData(uri: String, metaData: MediaMetaData, resolver: UnknownNameResolver)
  extends Ordered[SongData] {

  import SongData._

  /**
   * The title of the managed song. If this is specified by the meta
   * data, it is obtained from there. Otherwise, the last part of the URI is
   * returned.
   */
  lazy val getTitle: String = metaData.title getOrElse extractTitle(uri)

  /**
   * The name of the album this title belongs to. If available, this name is
   * obtained from meta data. Otherwise, the [[UnknownNameResolver]] is
   * queried.
   */
  lazy val getAlbum: String = metaData.album getOrElse resolver.unknownAlbumName

  /**
   * The name of the artist of this song. If available, this name is obtained
   * from meta data. Otherwise, the [[UnknownNameResolver]] is queried.
   */
  lazy val getArtist: String = metaData.artist getOrElse resolver.unknownArtistName

  /**
   * The duration of this song as bean property. If the duration is unknown,
   * result is less than zero.
   */
  lazy val getDuration: Int = metaData.duration getOrElse UnknownDuration

  /**
   * The track number as string bean property. If unknown, an empty string is
   * returned.
   */
  lazy val getTrackNumber: String = metaData.trackNumber map (_.toString) getOrElse
    UnknownTrackNumber

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
