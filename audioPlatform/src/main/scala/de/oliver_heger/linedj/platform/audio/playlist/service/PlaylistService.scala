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

package de.oliver_heger.linedj.platform.audio.playlist.service

import de.oliver_heger.linedj.platform.audio.playlist.Playlist
import de.oliver_heger.linedj.shared.archive.media.MediaFileID

import scala.annotation.tailrec

/**
  * Service implementation for the ''PlaylistService'' service.
  */
object PlaylistService extends
  de.oliver_heger.linedj.platform.audio.playlist.PlaylistService[Playlist, MediaFileID]:
  override def size(playlist: Playlist): Int =
    playlist.pendingSongs.size + playlist.playedSongs.size

  override def currentSong(playlist: Playlist): Option[MediaFileID] =
    playlist.pendingSongs.headOption

  override def currentIndex(playlist: Playlist): Option[Int] =
    currentSong(playlist) map (_ => playlist.playedSongs.size)

  override def moveForwards(playlist: Playlist): Option[Playlist] =
    playlist.pendingSongs match
      case h :: t =>
        Some(Playlist(pendingSongs = t, playedSongs = h :: playlist.playedSongs))
      case _ => None

  override def moveBackwards(playlist: Playlist): Option[Playlist] =
    playlist.playedSongs match
      case h :: t =>
        Some(Playlist(playedSongs = t, pendingSongs = h :: playlist.pendingSongs))
      case _ => None

  override def playlistEquals(playlist1: Playlist, playlist2: Playlist): Boolean =
    // Checks whether sub lists are equal in the part they have in common;
    // returns a list with the elements contained only in one of the lists.
    @tailrec def compareSubLists(lst1: SongList, lst2: SongList, idx: Int, delta: SongList):
    (Boolean, SongList) =
      if idx < 0 then compareSubLists(lst1, lst2.tail, idx + 1, lst2.head :: delta)
      else if idx > 0 then compareSubLists(lst1.tail, lst2, idx - 1, lst1.head :: delta)
      else lst1 match
        case h :: t if h == lst2.head => compareSubLists(t, lst2.tail, 0, delta)
        case h :: _ if h != lst2.head => (false, Nil)
        case _ => (true, delta)

    val pending1Cnt = playlist1.pendingSongs.size
    val played1Cnt = playlist1.playedSongs.size
    val pending2Cnt = playlist2.pendingSongs.size
    val played2Cnt = playlist2.playedSongs.size

    if pending1Cnt + played1Cnt != pending2Cnt + played2Cnt then false
    else if pending1Cnt == pending2Cnt then playlist1 == playlist2
    else
      val (eq1, delta1) = compareSubLists(playlist1.pendingSongs, playlist2.pendingSongs,
        pending1Cnt - pending2Cnt, Nil)
      if !eq1 then false
      else
        val (eq2, delta2) = compareSubLists(playlist1.playedSongs, playlist2.playedSongs,
          played1Cnt - played2Cnt, Nil)
        eq2 && (delta1 == delta2.reverse)

  override def incrementPlaylistSeqNo(seqNo: Int): Int =
    val next = seqNo + 1
    if next != SeqNoInitial then next
    else incrementPlaylistSeqNo(next)

  override def toSongList(playlist: Playlist): SongList =
    playlist.playedSongs.foldLeft(playlist.pendingSongs) { (l, s) => s :: l }

  override def toPlaylist(songs: SongList, currentIndex: Int): Option[Playlist] =
    if currentIndex < 0 || currentIndex >= songs.size then None
    else
      val (played, pending) = songs splitAt currentIndex
      Some(Playlist(playedSongs = played.reverse, pendingSongs = pending))
