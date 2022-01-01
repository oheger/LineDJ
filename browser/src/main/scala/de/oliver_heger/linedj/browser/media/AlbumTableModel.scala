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

package de.oliver_heger.linedj.browser.media

import de.oliver_heger.linedj.browser.media.AlbumTableModel.AlbumData
import de.oliver_heger.linedj.platform.audio.model.{SongData, SongTrackNoOrdering}

private object AlbumTableModel {
  /**
   * An empty table model object.
   */
  val empty: AlbumTableModel = new AlbumTableModel(Map.empty, Set.empty)

  /**
   * Creates a new ''AlbumTableModel'' instance wich is initialized with the
   * specified data items.
   *
   * @param items the data items to be added to the new model
   * @return the new model instance
   */
  def apply(items: Iterable[(AlbumKey, SongData)]): AlbumTableModel = {
    items.foldLeft(empty) { (model, item) =>
      model.add(item._1, item._2)
    }
  }

  /**
   * A helper class storing information about an album. It allows querying an
   * ordered song list which is dynamically constructed on demand.
   *
   * @param songs the list of songs
   * @param ordered a flag whether the list is ordered
   */
  private case class AlbumData(songs: List[SongData], ordered: Boolean) {
    /** The ordered song list.*/
    lazy val orderedSongs = createOrderedSongs()

    /**
     * Creates a new instance that contains the specified song.
     *
     * @param song the song to be added
     * @return the new instance
     */
    def add(song: SongData): AlbumData = {
      AlbumData(song :: songs, ordered = ordered && SongTrackNoOrdering.gt(song, songs.head))
    }

    /**
     * Creates an ordered list of songs based on the current song list.
     *
     * @return the ordered list of songs
     */
    private def createOrderedSongs() = if(ordered) songs.reverse
    else songs.sorted(SongTrackNoOrdering)
  }
}

/**
 * An internal helper class serving as a model for a table that displays the
 * albums of an artist.
 *
 * An instance of this class can manage the albums of multiple artists. A
 * sorted list of the songs on a specific albums can be queried (songs are
 * sorted by track number - if available - and name).
 *
 * The model is constructed by adding information about single songs step by
 * step in arbitrary order. This reflects the way in which meta data can come
 * in while the server scans its directory structure.
 *
 * @param data the map with the data of this model
 * @param songUris a set with the URIs of all contained songs
 */
private class AlbumTableModel(data: Map[AlbumKey, AlbumData], songUris: Set[String]) {
  /**
   * Returns a sequence with songs on the specified album. If the album cannot
   * be resolved, result is an empty sequence.
   *
   * @param key the key of the album
   * @return an ordered sequence with the songs on this album
   */
  def songsFor(key: AlbumKey): Seq[SongData] =
    data get key map(_.orderedSongs) getOrElse Nil

  /**
   * Creates a new model instance with the specified song added to an album.
   *
   * @param key the key of the album
   * @param song the song to be added
   * @return the new album instance
   */
  def add(key: AlbumKey, song: SongData): AlbumTableModel = {
    if (songUris contains song.id.uri) this
    else {
      val albumData = data.get(key).map(_.add(song)).getOrElse(AlbumData(List(song), ordered =
        true))
      new AlbumTableModel(data + (key -> albumData), songUris + song.id.uri)
    }
  }
}
