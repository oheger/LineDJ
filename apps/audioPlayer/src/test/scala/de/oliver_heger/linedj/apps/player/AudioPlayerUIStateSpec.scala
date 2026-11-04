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
import de.oliver_heger.linedj.platform.audio2.playlist.PlaylistService
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import de.oliver_heger.linedj.shared.archive.metadata.{Checksums, MediaMetadata}
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success}

object AudioPlayerUIStateSpec:
  /** A test medium ID. */
  private val TestMedium = Checksums.MediumChecksum("test-medium-id")

  /**
    * Generates an ID for a test file based on the given index.
    *
    * @param idx the index
    * @return the ID for this test file
    */
  private def fileID(idx: Int): String = "testFile_" + idx + "01234567890abcdef9876543210"

  /**
    * Generates a test song data object based on the given index.
    *
    * @param idx the index
    * @return the test song with this index
    */
  private def songData(idx: Int): SongData =
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
  private def undefinedSongData(idx: Int): SongData =
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
  private def songDataList(from: Int, to: Int)(generator: Int => SongData = songData): List[SongData] =
    (from to to).map(generator).toList

  /**
    * Returns a list with the IDs of the given songs.
    *
    * @param songs a list with songs
    * @return a list with the IDs of these songs
    */
  private def extractIDs(songs: List[SongData]): List[String] = songs.map(_.fileInfo.metadata.checksum)

  /**
    * Returns a map for the result of a resolve operation that contains the
    * provided songs.
    *
    * @param songs the list of songs
    * @return the resolve result containing these songs
    */
  private def resolveResult(songs: List[SongData]): Map[String, ArchiveModel.MediaFileInfo] =
    songs.map: songData =>
      songData.fileInfo.metadata.checksum -> songData.fileInfo
    .toMap
end AudioPlayerUIStateSpec

/**
  * Test class for [[AudioPlayerUIState]].
  */
class AudioPlayerUIStateSpec extends AnyFlatSpec, Matchers, OptionValues:

  import AudioPlayerUIStateSpec.*

  "The initial UI state" should "have correct properties" in :
    AudioPlayerUIState.Initial.playlist shouldBe empty
    AudioPlayerUIState.Initial.playlistSeqNo should be(PlaylistService.SeqNoInitial)
    AudioPlayerUIState.Initial.optCurrentIndex shouldBe empty
    AudioPlayerUIState.Initial.optCurrentSong shouldBe empty
    AudioPlayerUIState.Initial.idsToResolve shouldBe empty
    AudioPlayerUIState.Initial.pendingResolveCnt should be(0)

  /**
    * Creates an [[AudioPlayerState]] object from the provided parameters,
    * setting irrelevant properties to default values.
    *
    * @param songs        the list with song IDs
    * @param currentIndex the index of the current song
    * @param seqNo        the sequence number of the playlist
    * @return the corresponding [[AudioPlayerState]]
    */
  private def createPlayerState(songs: List[String], currentIndex: Int, seqNo: Int): AudioPlayerState =
    AudioPlayerState(
      playlist = PlaylistServiceImpl.toPlaylist(songs, currentIndex).value,
      playlistSeqNo = seqNo,
      playbackActive = false,
      playlistClosed = false,
      playlistActivated = false
    )

  "An AudioPlayerUIState" should "update itself for a new playlist" in :
    val songs = songDataList(1, 3)(undefinedSongData)
    val songIDs = extractIDs(songs)
    val CurrentIndex = 1
    val SeqNo = 28
    val playerState = createPlayerState(songIDs, CurrentIndex, SeqNo)

    val uiState = AudioPlayerUIState.Initial.playerStateChanged(playerState, PlaylistServiceImpl)

    uiState.playlist should contain theSameElementsInOrderAs songs
    uiState.playlistSeqNo should be(SeqNo)
    uiState.optCurrentSong.value should be(songs(CurrentIndex))
    uiState.optCurrentIndex.value should be(CurrentIndex)
    uiState.idsToResolve should contain theSameElementsAs songIDs
    uiState.pendingResolveCnt should be(1)

  it should "update itself for a modified current position" in :
    val songs = songDataList(1, 8)(undefinedSongData)
    val songIDs = extractIDs(songs)
    val CurrentIndex = 5
    val SeqNo = 29
    val playerState1 = createPlayerState(songIDs, 1, SeqNo)
    val playerState2 = createPlayerState(songIDs, CurrentIndex, SeqNo)

    val uiState1 = AudioPlayerUIState.Initial.playerStateChanged(playerState1, PlaylistServiceImpl)
    val uiState = uiState1.playerStateChanged(playerState2, PlaylistServiceImpl)

    uiState.playlist should be(uiState1.playlist)
    uiState.idsToResolve should be(uiState1.idsToResolve)
    uiState.playlistSeqNo should be(SeqNo)
    uiState.optCurrentSong.value should be(songs(CurrentIndex))
    uiState.optCurrentIndex.value should be(CurrentIndex)
    uiState.pendingResolveCnt should be(1)

  it should "return itself if there are no relevant changes in the player state" in :
    val songs = songDataList(1, 8)(undefinedSongData)
    val songIDs = extractIDs(songs)
    val playerState1 = createPlayerState(songIDs, 1, 30)
    val playerState2 = playerState1.copy(playlistClosed = true)

    val uiState1 = AudioPlayerUIState.Initial.playerStateChanged(playerState1, PlaylistServiceImpl)
    val uiState = uiState1.playerStateChanged(playerState2, PlaylistServiceImpl)

    uiState shouldBe theSameInstanceAs(uiState1)

  it should "handle a failed resolve operation" in :
    val songs = songDataList(1, 7)(undefinedSongData)
    val songIds = extractIDs(songs)
    val playerState = createPlayerState(songIds, 0, 31)

    val uiState1 = AudioPlayerUIState.Initial.playerStateChanged(playerState, PlaylistServiceImpl)
    val uiState = uiState1.fileInfoResolved(Failure(new IllegalStateException("Test exception: Resolve failed.")))

    uiState.playlist should be(uiState1.playlist)
    uiState.optCurrentIndex should be(uiState1.optCurrentIndex)
    uiState.optCurrentSong should be(uiState1.optCurrentSong)
    uiState.playlistSeqNo should be(uiState1.playlistSeqNo)
    uiState.idsToResolve shouldBe empty
    uiState.pendingResolveCnt should be(0)

  it should "handle a successful resolve operation" in :
    val CurrentIndex = 4
    val songs = songDataList(1, 12)(undefinedSongData)
    val resolvedSongs = songDataList(1, 12)()
    val songIDs = extractIDs(songs).slice(0, 10)
    val expectedSongs = songs.head :: resolvedSongs.slice(1, 10)
    val playerState = createPlayerState(songIDs, CurrentIndex, 32)

    val uiState1 = AudioPlayerUIState.Initial.playerStateChanged(playerState, PlaylistServiceImpl)
    val uiState = uiState1.fileInfoResolved(Success(resolveResult(resolvedSongs.drop(1))))

    uiState.optCurrentIndex should be(uiState1.optCurrentIndex)
    uiState.optCurrentSong.value should be(resolvedSongs(CurrentIndex))
    uiState.playlistSeqNo should be(uiState1.playlistSeqNo)
    uiState.idsToResolve shouldBe empty
    uiState.pendingResolveCnt should be(0)
    uiState.playlist should contain theSameElementsInOrderAs expectedSongs

  it should "handle multiple nested resolve operations" in :
    val songs1 = songDataList(1, 8)()
    val songs2 = songDataList(9, 16)()
    val playerState1 = createPlayerState(extractIDs(songs1), 0, 33)
    val playerState2 = createPlayerState(extractIDs(songs2), 1, 34)

    val uiState1 = AudioPlayerUIState.Initial.playerStateChanged(playerState1, PlaylistServiceImpl)
    val uiState2 = uiState1.playerStateChanged(playerState2, PlaylistServiceImpl)

    uiState2.pendingResolveCnt should be(2)
    uiState2.idsToResolve should contain theSameElementsAs extractIDs(songs2)

    val uiState3 = uiState2.fileInfoResolved(Success(resolveResult(songs1)))
    uiState3.pendingResolveCnt should be(1)
    uiState3.idsToResolve shouldBe empty
    uiState3.playlist should contain theSameElementsInOrderAs songDataList(9, 16)(undefinedSongData)

    val uiState = uiState3.fileInfoResolved(Success(resolveResult(songs2)))
    uiState.playlist should contain theSameElementsInOrderAs songs2
    uiState.playlistSeqNo should be(34)
    uiState.pendingResolveCnt should be(0)
