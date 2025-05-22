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

package de.oliver_heger.linedj.platform.audio.playlist.service

import de.oliver_heger.linedj.platform.audio.model.{SongData, SongDataFactory}
import de.oliver_heger.linedj.platform.audio.playlist.{MetadataResolveState, Playlist, PlaylistMetadata}
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Seq

object PlaylistMetadataServiceSpec:
  /** Medium ID used by tests. */
  private val TestMedium = MediumID("testMedium", Some("settings"))

  /** RegEx to parse a file ID and extract the test index. */
  private val PatternFileID =
    """testFile(\d+)\.wav""".r

  /**
    * Special implementation of a factory for songs. This implementation
    * checks whether meta is defined. It then returns song objects according to
    * the functions for defined or undefined metadata.
    */
  private val Factory: SongDataFactory = new SongDataFactory:
    override def createSongData(id: MediaFileID, metadata: MediaMetadata): SongData =
      if metadata.title.isDefined then
        SongData(id, metadata, metadata.title.get, metadata.artist.get, metadata.album.get)
      else
        val idx = extractIndex(id)
        undefinedSongData(idx)

  /**
    * Generates an ID for the test media file with the given index.
    *
    * @param idx the index
    * @return the ID for this test file
    */
  private def fileID(idx: Int): MediaFileID =
    MediaFileID(TestMedium, s"testFile$idx.wav")

  /**
    * Extracts the index of a test file from a file ID.
    *
    * @param id the file ID
    * @return the index that has been extracted
    */
  private def extractIndex(id: MediaFileID): Int =
    id.uri match
      case PatternFileID(idx) => idx.toInt
      case _ => throw new AssertionError("Unexpected file name " + id)

  /**
    * Generates test metadata for the file with the given index.
    *
    * @param idx the index
    * @return metadata for this file
    */
  private def createMetadata(idx: Int): MediaMetadata =
    MediaMetadata(
      title = Some("Title" + idx), 
      artist = Some("Artist" + idx),
      album = Some("Album" + idx),
      size = idx * 100 + 1,
      checksum = s"check$idx"
    )

  /**
    * Generates a ''SongData'' instance with defined metadata.
    *
    * @param idx the index
    * @return the defined ''SongData''
    */
  private def definedSongData(idx: Int): SongData =
    val metaData = createMetadata(idx)
    SongData(fileID(idx), metaData, metaData.title.get, metaData.artist.get,
      metaData.album.get)

  /**
    * Generates a ''SongData'' instance if metadata is not available.
    *
    * @param idx the index
    * @return the undefined ''SongData''
    */
  private def undefinedSongData(idx: Int): SongData =
    SongData(fileID(idx), MediaMetadata.UndefinedMediaData, "undefinedTitle" + idx, "undefinedArtist" + idx,
      "undefinedAlbum" + idx)

  /**
    * Creates a playlist instance that contains test songs in the given range.
    *
    * @param from the from index
    * @param to   the to index (including)
    * @return the playlist
    */
  private def createPlaylist(from: Int, to: Int): Playlist =
    val files = (from to to).map(i => fileID(i)).toList
    Playlist(pendingSongs = files, playedSongs = Nil)

  /**
    * Generates a map with metadata for the test files with the specified
    * indices.
    *
    * @param indices the indices of files with metadata
    * @return the map with metadata
    */
  private def createMetaDataMap(indices: Int*): Map[MediaFileID, MediaMetadata] =
    indices.foldLeft(Map.empty[MediaFileID, MediaMetadata]) { (m, i) =>
      m + (fileID(i) -> createMetadata(i))
    }

  /**
    * Generates metadata for all files contained in the specified ranges. This
    * is convenient to generate whole chunks of metadata.
    *
    * @param ranges a sequence with ranges
    * @return the map with metadata
    */
  private def metaDataForRanges(ranges: (Int, Int)*): Map[MediaFileID, MediaMetadata] =
    createMetaDataMap(ranges.flatMap(r => r._1 to r._2): _*)

  /**
    * Generates a sequence of resolved songs in the specified range. The songs
    * are assigned their index in the playlist, in the same way as this is done
    * by the service.
    *
    * @param from the from index
    * @param to   the to index (including)
    * @return the sequence of resolved songs
    */
  private def createResolvedSongs(from: Int, to: Int): Seq[(SongData, Int)] =
    (from to to).map(i => (definedSongData(i), i - 1))

/**
  * Test class for ''PlaylistMetaDataService''.
  */
class PlaylistMetadataServiceSpec extends AnyFlatSpec with Matchers:

  import PlaylistMetadataServiceSpec._

  "A PlaylistMetadataService" should "process an unchanged playlist" in:
    val state = PlaylistMetadataService.InitialState

    val (delta, nextState) = PlaylistMetadataService.processPlaylistUpdate(createPlaylist(1, 4),
      state.seqNo, state)(Factory)
    nextState should be(state)
    delta.fullUpdate shouldBe false
    delta.resolvedSongs shouldBe empty
    delta.updatedRanges shouldBe empty

  it should "process a new playlist" in:
    val Count = 8
    val playlist = createPlaylist(1, Count)
    val songs = (1 to Count).map(i => (undefinedSongData(i), i - 1))

    val (delta, nextState) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      PlaylistMetadataService.InitialState)(Factory)
    nextState.seqNo should be(1)
    delta.fullUpdate shouldBe true
    delta.updatedRanges should contain only ((0, Count - 1))
    delta.resolvedSongs should contain theSameElementsAs songs

  it should "process a new playlist with played songs" in:
    val Count = 16
    val playlist = PlaylistService.moveForwards(
      PlaylistService.moveForwards(createPlaylist(1, Count)).get).get
    val songs = (1 to Count).map(i => (undefinedSongData(i), i - 1))

    val (delta, _) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      PlaylistMetadataService.InitialState)(Factory)
    delta.resolvedSongs should contain theSameElementsAs songs

  it should "process metadata if no songs can be resolved" in:
    val playlist = createPlaylist(1, 4)
    val metaData = metaDataForRanges((5, 8))
    val (_, state) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      PlaylistMetadataService.InitialState)(Factory)

    val (delta, nextState) = PlaylistMetadataService.processMetadataUpdate(
      PlaylistMetadata(metaData), state)(Factory)
    delta.resolvedSongs shouldBe empty
    delta.updatedRanges shouldBe empty
    delta.fullUpdate shouldBe false
    nextState.metadata should be(metaData)

  it should "resolve songs when new metadata arrives" in:
    val playlist = createPlaylist(1, 8)
    val resolvedRange = (2, 5)
    val metaData = metaDataForRanges(resolvedRange)
    val resolvedSongs = createResolvedSongs(resolvedRange._1, resolvedRange._2)
    val (_, state) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      PlaylistMetadataService.InitialState)(Factory)

    val (delta, nextState) = PlaylistMetadataService.processMetadataUpdate(
      PlaylistMetadata(metaData), state)(Factory)
    delta.resolvedSongs should contain theSameElementsAs resolvedSongs
    delta.updatedRanges should contain only ((resolvedRange._1 - 1, resolvedRange._2 - 1))
    delta.fullUpdate shouldBe false
    nextState.metadata should be(metaData)

  it should "resolve all songs in multiple steps" in:
    val playlist = createPlaylist(1, 8)
    val (_, state1) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      PlaylistMetadataService.InitialState)(Factory)

    def checkMetaDataUpdate(range: (Int, Int), state: MetadataResolveState):
    MetadataResolveState =
      val (delta, nextState) = PlaylistMetadataService.processMetadataUpdate(
        PlaylistMetadata(metaDataForRanges((1, range._2))), state)(Factory)
      delta.resolvedSongs should contain theSameElementsAs createResolvedSongs(range._1, range._2)
      delta.updatedRanges should contain only ((range._1 - 1, range._2 - 1))
      nextState

    val state2 = checkMetaDataUpdate((1, 3), state1)
    val state3 = checkMetaDataUpdate((4, 8), state2)
    state3.unresolvedSongs shouldBe empty

  it should "return correct update indices if multiple ranges are involved" in:
    val playlist = createPlaylist(1, 16)
    val (_, state1) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      PlaylistMetadataService.InitialState)(Factory)
    val ranges = List((1, 4), (8, 9), (11, 11), (15, 16))
    val rangesZeroBase = ranges map (r => (r._1 - 1, r._2 - 1))
    val metaData = metaDataForRanges(ranges: _*)

    val (delta, _) = PlaylistMetadataService.processMetadataUpdate(PlaylistMetadata(metaData),
      state1)(Factory)
    delta.resolvedSongs should have size 9
    delta.updatedRanges should contain theSameElementsAs rangesZeroBase

  it should "resolve songs in a new playlist if metadata is available" in:
    val Count = 8
    val resolvedFrom = 3
    val resolvedTo = 5
    val playlist = createPlaylist(1, Count)
    val songs = (1 to Count).map { i =>
      val data = if i >= resolvedFrom && i <= resolvedTo then createResolvedSongs(i, i).head._1
      else undefinedSongData(i)
      (data, i - 1)
    }
    val state = MetadataResolveState(0, List((fileID(1), 0), (fileID(5), 4)),
      createMetaDataMap(resolvedFrom to resolvedTo: _*))

    val (delta, nextState) = PlaylistMetadataService.processPlaylistUpdate(playlist, 1,
      state)(Factory)
    nextState.metadata should be(state.metadata)
    nextState.seqNo should be(1)
    delta.fullUpdate shouldBe true
    delta.updatedRanges should contain only ((0, Count - 1))
    delta.resolvedSongs should contain theSameElementsAs songs
