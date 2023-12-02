/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.platform.audio.playlist

/**
  * Interface for a service that allows basic operations on [[Playlist]]
  * objects.
  *
  * With the functions offered by this service the most basic properties of a
  * playlist can be queried. It is also possible to do some operations on
  * such objects.
  *
  * @tparam Playlist the type for the playlist
  * @tparam Song     the type for the songs in the playlist
  */
trait PlaylistService[Playlist, Song]:
  /** Constant for a sequence number indicating an initial playlist. */
  final val SeqNoInitial = 0

  /**
    * Type definition for a list of songs. A ''Playlist'' may consist of one or
    * multiple lists of this type. The difference between a ''SongList'' and a
    * ''Playlist'' is that the former is only a sequence of songs while the
    * latter also defines a position, i.e. the current song to be played.
    */
  type SongList = List[Song]

  /**
    * Returns the size of the specified ''Playlist''. This is the number of
    * songs contained in this list.
    *
    * @param playlist the ''Playlist''
    * @return the total number of songs in this list
    */
  def size(playlist: Playlist): Int

  /**
    * Returns the current song of the specified playlist. If all songs have
    * already been played, result is ''None''.
    *
    * @param playlist the ''Playlist''
    * @return an ''Option'' for the current song in the playlist
    */
  def currentSong(playlist: Playlist): Option[Song]

  /**
    * Returns the (0-based) index of the current song in the specified
    * playlist. If the playlist has no current song, result is ''None''.
    *
    * @param playlist the ''Playlist''
    * @return an ''Option'' with the index of the current song
    */
  def currentIndex(playlist: Playlist): Option[Int]

  /**
    * Returns a new ''Playlist'' instance whose current position is moved to
    * the next song. This method can be used for instance when playback of the
    * current song has finished, to switch to the next song which is pending.
    * If the current song was the last song, result is ''None''.
    *
    * @param playlist the ''Playlist''
    * @return an ''Option'' for the playlist moved to the next song
    */
  def moveForwards(playlist: Playlist): Option[Playlist]

  /**
    * Returns a new ''Playlist'' instance whose current position is moved to
    * the previous song. With this function it is possible to play songs again
    * that have already been played. If the current song in the playlist is the
    * first song, result is ''None''.
    *
    * @param playlist the ''Playlist''
    * @return an ''Option'' for the playlist moved to the previous song
    */
  def moveBackwards(playlist: Playlist): Option[Playlist]

  /**
    * Checks two playlist instances of equality.
    *
    * Two playlist instances are considered equal if they contain the same
    * songs in the same order. They may differ in the position of the current
    * song, however.
    *
    * Because a playlist consists of multiple lists (for pending songs and
    * songs that have already been played), it is not trivial to determine
    * whether two playlist instances contain the same sequence of songs. If the
    * instances just differ in the position of the current song, they look
    * structurally different. This function handles such differences and
    * compares the sequence of songs, ignoring the current position.
    *
    * @param playlist1 the first playlist
    * @param playlist2 the 2nd playlist
    * @return a flag whether these instances are equal in the sense described
    */
  def playlistEquals(playlist1: Playlist, playlist2: Playlist): Boolean

  /**
    * Increments a playlist sequence number. Playlist objects are assigned a
    * sequence number, so that it can be determined easily when there is a
    * change. This function increments such an index and makes sure that the
    * reserved initial index cannot be reached when there is an overflow in the
    * sequence.
    *
    * @param seqNo the current sequence number
    * @return the increased sequence number
    */
  def incrementPlaylistSeqNo(seqNo: Int): Int

  /**
    * Transforms the given ''Playlist'' into a list of songs that contains all
    * songs in order, independent from the current position of the playlist.
    * With this function a canonical representation of the sequence of songs to
    * be played can be obtained.
    *
    * @param playlist the ''Playlist''
    * @return an ordered list with all songs in this playlist
    */
  def toSongList(playlist: Playlist): SongList

  /**
    * Generates a ''Playlist'' from a sequence of songs and the index of the
    * current song. If the index is invalid (less than zero or greater or equal
    * than the size of the playlist), result is ''None''.
    *
    * @param songs        the sequence of songs
    * @param currentIndex the index of the current song (0-based)
    * @return an ''Option'' with the resulting ''Playlist''
    */
  def toPlaylist(songs: SongList, currentIndex: Int): Option[Playlist]

  /**
    * Returns a ''Playlist'' with an updated current song. This function
    * creates a ''Playlist'' with the same songs as the original one, but the
    * song with the specified index becomes the current song. If the index is
    * invalid (less than zero or greater or equal than the size of the
    * playlist), result is ''None''.
    *
    * @param playlist     the original ''Playlist''
    * @param currentIndex the index of the new current song (0-based)
    * @return an ''Option'' with the resulting ''Playlist''
    */
  def setCurrentSong(playlist: Playlist, currentIndex: Int): Option[Playlist] =
    val songs = toSongList(playlist)
    toPlaylist(songs, currentIndex)
