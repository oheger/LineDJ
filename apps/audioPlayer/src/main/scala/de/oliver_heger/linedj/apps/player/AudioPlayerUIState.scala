/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.player

import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.audio2.AudioPlayerState
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}

import scala.util.Try

private object AudioPlayerUIState:
  /**
    * Constant for an initial [[AudioPlayerUIState]] instance. The audio player
    * UI application starts with this state instance.
    */
  final val Initial: AudioPlayerUIState = AudioPlayerUIState(
    playlist = Nil,
    playlistSeqNo = PlaylistService.SeqNoInitial,
    optCurrentIndex = None,
    optCurrentSong = None,
    idsToResolve = Nil,
    pendingResolveCnt = 0
  )

  /**
    * The length of a song title for an undefined song. Such songs get their
    * title from the ID of the associated media file. Since this ID is quite
    * long, it gets stripped to the length specified here.
    */
  final val UndefinedSongDataTitleLength = 25

  /** Constant for the ID of an undefined medium. */
  private val UndefinedMediumID = Checksums.MediumChecksum("")

  /**
    * Creates a [[SongData]] object with minimum properties to reflect the song
    * with the given ID. Such objects are set in the table model until the
    * metadata of media files has been resolved.
    *
    * @param fileID the ID of the associated file
    * @return the [[SongData]] for this media file
    */
  private def undefinedSongData(fileID: String): SongData =
    val fileInfo = ArchiveModel.MediaFileInfo(
      fileUri = MediaFileUri(fileID),
      mediumID = UndefinedMediumID,
      metadata = MediaMetadata.UndefinedMediaData.copy(
        title = Some(fileID.take(UndefinedSongDataTitleLength)),
        checksum = fileID
      )
    )
    SongData(fileInfo)
end AudioPlayerUIState

/**
  * A data class representing the state of the audio player UI.
  *
  * An instance is passed to the different components that make up the UI. They
  * can then figure out whether they need to update themselves. The class also
  * has functionality to modify the state as reaction to external triggers.
  *
  * @param playlist          an ordered list with songs in the playlist
  * @param playlistSeqNo     the sequence number of the current playlist
  * @param optCurrentSong    an [[Option]] with the current song to play
  * @param optCurrentIndex   an [[Option]] with the index of the current song
  * @param idsToResolve      the IDs of media files to be resolved
  * @param pendingResolveCnt number of pending resolve operations
  */
private case class AudioPlayerUIState(playlist: List[SongData],
                                      playlistSeqNo: Int,
                                      optCurrentSong: Option[SongData],
                                      optCurrentIndex: Option[Int],
                                      idsToResolve: List[String],
                                      pendingResolveCnt: Int):

  import AudioPlayerUIState.*

  /**
    * Returns an updated copy of this object that incorporates the changes of
    * the provided [[AudioPlayerState]]. The properties of the passed in state
    * are aligned with information hold by this class.
    *
    * @param state           the new [[AudioPlayerState]]
    * @param playlistService the service to manage a [[Playlist]]
    * @return the updated [[AudioPlayerUIState]]
    */
  def playerStateChanged(state: AudioPlayerState,
                         playlistService: PlaylistService[Playlist, String]): AudioPlayerUIState =
    if state.playlistSeqNo != playlistSeqNo then
      val newPlaylist = playlistService.toSongList(state.playlist)
      AudioPlayerUIState(
        playlist = newPlaylist.map(undefinedSongData),
        playlistSeqNo = state.playlistSeqNo,
        optCurrentSong = playlistService.currentSong(state.playlist).map(undefinedSongData),
        optCurrentIndex = playlistService.currentIndex(state.playlist),
        idsToResolve = newPlaylist,
        pendingResolveCnt = pendingResolveCnt + 1
      )
    else
      val newCurrentIndex = playlistService.currentIndex(state.playlist)
      if newCurrentIndex != optCurrentIndex then
        val newCurrentSong = newCurrentIndex.map(idx => playlist(idx))
        copy(optCurrentIndex = newCurrentIndex, optCurrentSong = newCurrentSong)
      else
        this

  /**
    * Returns an updated copy of this object that incorporates the changes of
    * the provided result of a media file resolve operation. All songs that are
    * lacking metadata and are contained in the result are updated.
    *
    * @param result the result of the resolver service
    * @return the updated [[AudioPlayerUIState]]
    */
  def fileInfoResolved(result: Try[Map[String, ArchiveModel.MediaFileInfo]]): AudioPlayerUIState =
    val newPlaylist = result.map: songsMap =>
      playlist.map: song =>
        songsMap.get(song.fileInfo.metadata.checksum).map(SongData.apply).getOrElse(song)
    .getOrElse(playlist)
    val newCurrent = optCurrentIndex.map(index => newPlaylist(index))
    copy(
      idsToResolve = Nil,
      pendingResolveCnt = pendingResolveCnt - 1,
      playlist = newPlaylist,
      optCurrentSong = newCurrent
    )
