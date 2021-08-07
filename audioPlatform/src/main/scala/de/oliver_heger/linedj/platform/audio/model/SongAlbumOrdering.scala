/*
 * Copyright 2015-2021 The Developers Team.
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

/**
  * An ''Ordering'' implementation for [[SongData]] which orders songs based on
  * the albums they belong to.
  *
  * This ordering can be used for instance to bring all the songs of an artist
  * into a logic order. It applies the following criteria:
  *
  * First the inception year of the album is evaluated. Older albums are
  * sorted before newer ones. Albums for which the inception year is unknown
  * are sorted after albums with a defined inception year.
  *
  * Albums with the same inception year are sorted by their names in alphabetic
  * order (ignoring case).
  *
  * The songs belonging to an album are sorted by using
  * [[SongTrackNoOrdering]].
  */
object SongAlbumOrdering extends Ordering[SongData] {
  override def compare(x: SongData, y: SongData): Int = {
    val c = extractInceptionYear(x) - extractInceptionYear(y)
    if (c != 0) c
    else {
      val c1 = x.album compareToIgnoreCase y.album
      if (c1 != 0) c1
      else SongTrackNoOrdering.compare(x, y)
    }
  }

  /**
    * Obtains the inception year from the given song data. Ensures that songs
    * without a year are sorted after others.
    *
    * @param s the ''SongData''
    * @return the inception year of this song
    */
  private def extractInceptionYear(s: SongData): Int =
    s.metaData.inceptionYear getOrElse Integer.MAX_VALUE
}
