/*
 * Copyright 2015-2022 The Developers Team.
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
  * An ''Ordering'' implementation for [[SongData]] which orders songs by their
  * track number.
  *
  * If the track number of two songs is equal, they are ordered by their title.
  */
object SongTrackNoOrdering extends Ordering[SongData] {
  override def compare(x: SongData, y: SongData): Int = {
    val c = extractTrackNumber(x) - extractTrackNumber(y)
    if (c != 0) c else java.lang.String.CASE_INSENSITIVE_ORDER.compare(x.title, y.title)
  }

  /**
    * Extracts the track number. If this is undefined for the given song, the
    * maximum number is returned. (Songs without a track number are sorted
    * after other songs.)
    *
    * @param s the song
    * @return the track number
    */
  private def extractTrackNumber(s: SongData): Int =
    s.metaData.trackNumber getOrElse Integer.MAX_VALUE
}
