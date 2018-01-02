/*
 * Copyright 2015-2017 The Developers Team.
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

import de.oliver_heger.linedj.platform.audio.model.SongDataFactory
import de.oliver_heger.linedj.platform.audio.playlist.{MetaDataResolveDelta,
  MetaDataResolveState, Playlist, PlaylistMetaData}
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * Implementation of the playlist meta data service.
  */
object PlaylistMetaDataService
  extends de.oliver_heger.linedj.platform.audio.playlist.PlaylistMetaDataService {
  /** Constant for undefined meta data. */
  private val EmptyMetaData = MediaMetaData()

  /** Constant for a delta that does not contain any updates. */
  private val EmptyDelta = MetaDataResolveDelta(List.empty, List.empty, fullUpdate = false)

  override val InitialState: MetaDataResolveState =
    MetaDataResolveState(PlaylistService.SeqNoInitial, List.empty, Map.empty)

  override def processPlaylistUpdate(playlist: Playlist, seqNo: Int, state: MetaDataResolveState)
  : SongDataFactory => (MetaDataResolveDelta, MetaDataResolveState) = { factory =>
    if (state.seqNo == seqNo) (EmptyDelta, state)
    else processNewPlaylist(playlist, seqNo, state, factory)
  }

  override def processMetaDataUpdate(data: PlaylistMetaData, state: MetaDataResolveState):
  SongDataFactory => (MetaDataResolveDelta, MetaDataResolveState) = {
    factory => {
      val (resolved, unresolved) = state.unresolvedSongs.partition(data.data contains _._1)
      if (resolved.isEmpty) (EmptyDelta, state.copy(metaData = data.data))
      else {
        val rangeList = calcUpdatedIndices(resolved)
        val resolvedSongs = resolved.map(e => (factory.createSongData(e._1, data.data(e._1)), e._2))
        (MetaDataResolveDelta(resolvedSongs, rangeList, fullUpdate = false),
          state.copy(metaData = data.data, unresolvedSongs = unresolved))
      }
    }
  }

  /**
    * Handles a changed playlist. In this case, ''SongData'' objects with
    * undefined meta data are created, and the client is told to do a full
    * update. If meta data is available, it may be possible to already resolve
    * some of the songs in the new playlist.
    *
    * @param playlist the updated playlist
    * @param seqNo    the playlist sequence number
    * @param state    the current state
    * @param factory  the factory for songs
    * @return a tuple with a delta and an updated state
    */
  private def processNewPlaylist(playlist: Playlist, seqNo: Int, state: MetaDataResolveState,
                                 factory: SongDataFactory):
  (MetaDataResolveDelta, MetaDataResolveState) = {
    val songs = PlaylistService.toSongList(playlist)
      .map(s => factory.createSongData(s, state.metaData.getOrElse(s, EmptyMetaData)))
      .zipWithIndex
    val unresolved = songs.map(e => (e._1.id, e._2))
    (MetaDataResolveDelta(resolvedSongs = songs, updatedRanges = List((0, songs.size - 1)),
      fullUpdate = true), state.copy(seqNo = seqNo, unresolvedSongs = unresolved))
  }

  /**
    * Determines a list with indices to update based on a list of resolved
    * songs. The resulting list can be used to determine the minimum ranges in
    * a table structure (such as a table model in the UI) that are affected by
    * the newly resolved songs.
    *
    * @param resolved the list with resolved songs and their indices
    * @return a list with ranges that cover the indices of updated songs
    */
  private def calcUpdatedIndices(resolved: List[(MediaFileID, Int)]): List[(Int, Int)] = {
    val init = (List.empty[(Int, Int)], (-1, 0))
    val (ranges, last) = resolved.foldLeft(init) { (s, e) =>
      val (lst, (i1, idxLast)) = s
      val curIdx = e._2
      val idxStart = if (i1 < 0) curIdx else i1
      if (idxLast + 1 != e._2) {
        if (i1 < 0) (lst, (curIdx, curIdx))
        else ((idxStart, idxLast) :: lst, (curIdx, curIdx))
      } else (lst, (idxStart, curIdx))
    }
    last :: ranges
  }
}
