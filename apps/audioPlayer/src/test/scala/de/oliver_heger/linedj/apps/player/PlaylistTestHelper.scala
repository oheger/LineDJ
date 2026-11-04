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
import de.oliver_heger.linedj.platform.audio2.impl.PlaylistServiceImpl
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}

/**
  * A test helper object that offers functionality to create test data required
  * to construct playlists and audio player state.
  */
object PlaylistTestHelper:
  /** A test medium ID. */
  private val TestMedium = Checksums.MediumChecksum("test-medium-id")

  /**
    * Generates an ID for a test file based on the given index.
    *
    * @param idx the index
    * @return the ID for this test file
    */
  def fileID(idx: Int): String = "testFile_" + idx + "01234567890abcdef9876543210"

  /**
    * Generates a test song data object based on the given index.
    *
    * @param idx the index
    * @return the test song with this index
    */
  def songData(idx: Int): SongData =
    val fileInfo = ArchiveModel.MediaFileInfo(
      fileUri = MediaFileUri("testFileUri" + idx),
      mediumID = TestMedium,
      metadata = MediaMetadata.UndefinedMediaData.copy(title = Some("Title" + idx), checksum = fileID(idx))
    )
    SongData(fileInfo)

  /**
    * Generates a [[SongData]] object which does not contain metadata.
    *
    * @param idx the index of the test song
    * @return the undefined [[SongData]] object with this index
    */
  def undefinedSongData(idx: Int): SongData =
    val fid = fileID(idx)
    val fileInfo = ArchiveModel.MediaFileInfo(
      fileUri = MediaFileUri(fid),
      mediumID = Checksums.MediumChecksum(""),
      metadata = MediaMetadata.UndefinedMediaData.copy(
        checksum = fid,
        title = Some(fid.take(AudioPlayerUIState.UndefinedSongDataTitleLength))
      )
    )
    SongData(fileInfo)

  /**
    * Generates a list of test [[SongData]] objects within the given range.
    *
    * @param from      the start index
    * @param to        the end index (including)
    * @param generator the generator function
    * @return the resulting list with test songs
    */
  def songDataList(from: Int, to: Int)(generator: Int => SongData = songData): List[SongData] =
    (from to to).map(generator).toList

  /**
    * Returns a list with the IDs of the given songs.
    *
    * @param songs a list with songs
    * @return a list with the IDs of these songs
    */
  def extractIDs(songs: List[SongData]): List[String] = songs.map(_.fileInfo.metadata.checksum)

  /**
    * Returns a map for the result of a resolve operation that contains the
    * provided songs.
    *
    * @param songs the list of songs
    * @return the resolve result containing these songs
    */
  def resolveResult(songs: List[SongData]): Map[String, ArchiveModel.MediaFileInfo] =
    songs.map: songData =>
      songData.fileInfo.metadata.checksum -> songData.fileInfo
    .toMap

  /**
    * Creates an [[AudioPlayerState]] object from the provided parameters,
    * setting irrelevant properties to default values.
    *
    * @param songs        the list with song IDs
    * @param currentIndex the index of the current song
    * @param seqNo        the sequence number of the playlist
    * @return the corresponding [[AudioPlayerState]]
    */
  def createPlayerState(songs: List[String], currentIndex: Int, seqNo: Int): AudioPlayerState =
    AudioPlayerState(
      playlist = PlaylistServiceImpl.toPlaylist(songs, currentIndex).get,
      playlistSeqNo = seqNo,
      playbackActive = false,
      playlistClosed = false,
      playlistActivated = false
    )

  /**
    * Constructs a new [[AudioPlayerUIState]] by applying the changes reflected 
    * by an [[AudioPlayerState]] to it.
    *
    * @param playerState the changed [[AudioPlayerState]]
    * @param uiState     the original [[AudioPlayerUIState]]
    * @return the updated [[AudioPlayerUIState]]
    */
  def updateUiState(playerState: AudioPlayerState,
                    uiState: AudioPlayerUIState = AudioPlayerUIState.Initial): AudioPlayerUIState =
    uiState.playerStateChanged(playerState, PlaylistServiceImpl)
