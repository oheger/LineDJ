/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.platform.audio.SetPlaylist
import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}

import scala.annotation.tailrec

/**
  * A trait providing common functionality used by test classes related to
  * playlist processing.
  */
trait PlaylistTestHelper:
  /** A vector with test medium IDs. */
  val MediaIDs = Vector(MediumID("medium1", Some("medium1.settings"), "component1"),
    MediumID("medium2", None, "component2"),
    MediumID("medium3", None),
    MediumID("medium4", Some("medium34.settings")))

  /**
    * Generates the URI of a test song based on the given index.
    *
    * @param idx the index
    * @return the URI of this test song
    */
  def songUri(idx: Int): String =
    s"song://TestSong_$idx.mp3"

  /**
    * Returns the medium ID for the test song with the specified index.
    *
    * @param idx the index
    * @return the medium ID for this test song
    */
  def mediumFor(idx: Int): MediumID = MediaIDs(idx % MediaIDs.size)

  /**
    * Generates a file ID for a test file.
    *
    * @param idx the index
    * @return the file ID for this test file
    */
  def fileId(idx: Int): MediaFileID = MediaFileID(mediumFor(idx), songUri(idx))

  /**
    * Generates a test playlist consisting of the given number of test songs.
    *
    * @param length     the length of the playlist
    * @param currentIdx the index of the current song (0-based)
    * @return the resulting playlist
    */
  def generatePlaylist(length: Int, currentIdx: Int): Playlist =
    @tailrec def moveToCurrent(pl: Playlist): Playlist =
      if pl.playedSongs.lengthCompare(currentIdx) < 0 then
        moveToCurrent(PlaylistService.moveForwards(pl).get)
      else pl

    val songs = (0 until length).foldRight(List.empty[MediaFileID]) { (i, lst) =>
      fileId(i) :: lst
    }
    moveToCurrent(Playlist(songs, Nil))

  /**
    * Generates a test playlist in which all media file IDs have the specified
    * checksum value.
    *
    * @param length     the length of the playlist
    * @param currentIdx the index of the current song (0-based)
    * @param checksum   the checksum for the single IDs
    * @return the resulting playlist
    */
  def generatePlaylistWithChecksum(length: Int, currentIdx: Int, checksum: String): Playlist =
    val pl = generatePlaylist(length, currentIdx)
    pl.copy(pendingSongs = pl.pendingSongs map (_.copy(checksum = Some(checksum))))

  /**
    * Generates a ''SetPlaylist'' command based on the given parameters.
    *
    * @param length         the length of the playlist
    * @param currentIdx     the index of the current song (0-based)
    * @param positionOffset position offset in the current song
    * @param timeOffset     time offset in the current song
    * @return the resulting ''SetPlaylist'' command
    */
  def generateSetPlaylist(length: Int, currentIdx: Int, positionOffset: Long = 0,
                          timeOffset: Long = 0): SetPlaylist =
    SetPlaylist(playlist = generatePlaylist(length, currentIdx), positionOffset = positionOffset,
      timeOffset = timeOffset)
