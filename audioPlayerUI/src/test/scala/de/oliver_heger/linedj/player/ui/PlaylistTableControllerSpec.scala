/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.ui

import java.util
import java.util.Collections

import de.oliver_heger.linedj.platform.audio.AudioPlayerState
import de.oliver_heger.linedj.platform.audio.model.{SongData, SongDataFactory}
import de.oliver_heger.linedj.platform.audio.playlist._
import de.oliver_heger.linedj.shared.archive.media.{MediaFileID, MediumID}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object PlaylistTableControllerSpec:
  /** A test medium ID. */
  private val TestMedium = MediumID("testMedium", Some("settings"))

  /** The number of songs in the initial test playlist. */
  private val InitPlaylistSize = 8

  /** The initial metadata state. */
  private val InitMetaDataState = metaDataState(0)

  /**
    * A special song assigned to elements in the table model collection that
    * have not yet been updated
    */
  private val InitSongData = songData(0)

  /**
    * Generates an ID for a test file based on the given index.
    *
    * @param idx the index
    * @return the ID for this test file
    */
  private def fileID(idx: Int): MediaFileID =
    MediaFileID(TestMedium, "testFile_" + idx)

  /**
    * Generates a test ''SongData'' object based on the given index.
    *
    * @param idx the index
    * @return the test song with this index
    */
  private def songData(idx: Int): SongData =
    SongData(fileID(idx), MediaMetadata(title = Some("Title" + idx)), "Title" + idx,
      "Artist" + idx, "Album" + idx)

  /**
    * Generates a tuple with a test ''SongData'' and its index in the playlist.
    * Such objects are needed by metadata resolve delta structures.
    *
    * @param idx the index
    * @return the test song and its index
    */
  private def songDataIdx(idx: Int): (SongData, Int) = (songData(idx), idx - 1)

  /**
    * Generates a list of test ''SongData'' objects with the given number of
    * elements. The ''ith'' element is the test song with this index
    *
    * @param count the number of elements
    * @return the resulting list with test songs
    */
  private def songDataList(count: Int): List[SongData] =
    (1 to count).map(songData).toList

  /**
    * Generates a test ''MetaDataResolveState'' object based on the given
    * index. The content of the state does not really matter; this is only
    * needed to simulate updated states.
    *
    * @param idx the index
    * @return the ''MetaDataResolveState'' with this index
    */
  private def metaDataState(idx: Int): MetadataResolveState =
    MetadataResolveState(idx, List.empty, Map.empty)

/**
  * Test class for ''PlaylistTableController''.
  */
class PlaylistTableControllerSpec extends AnyFlatSpec with Matchers with MockitoSugar:

  import PlaylistTableControllerSpec._

  /**
    * Generates a test ''AudioPlayerState'' object based on mock objects. The
    * actual content does not matter; it only has to be checked whether the
    * correct properties are passed to the metadata service.
    *
    * @return the ''AudioPlayerState''
    */
  private def playerState(): AudioPlayerState =
    AudioPlayerState(mock[Playlist], 42, playbackActive = true, playlistClosed = false,
      playlistActivated = true)

  /**
    * Generates a test object with playlist metadata. The actual content does
    * not matter, as this object is passed only as input parameter to a mock
    * service object.
    *
    * @return the ''PlaylistMetaData''
    */
  private def playlistMetaData(): PlaylistMetadata = mock[PlaylistMetadata]

  "A PlaylistTableController" should "populate the model for a full playlist update" in:
    val SongCount = 2 * InitPlaylistSize
    val state = playerState()
    val delta = MetadataResolveDelta(songDataList(SongCount).zipWithIndex, Nil, fullUpdate = true)
    val helper = new ControllerTestHelper

    helper.expectPlaylistUpdate(state, delta, metaDataState(1))
      .triggerPlaylistUpdate(state)
      .checkModelForFullUpdate(SongCount)

  it should "notify the table handler about a full playlist update" in:
    val state = playerState()
    val helper = new ControllerTestHelper

    helper.expectPlaylistUpdate(state, MetadataResolveDelta(songDataList(4).zipWithIndex, Nil,
      fullUpdate = true), metaDataState(1))
      .triggerPlaylistUpdate(state)
      .verifyTableDataChangeNotification()

  it should "handle a playlist update that does not require an action" in:
    val state = playerState()
    val helper = new ControllerTestHelper

    helper.expectPlaylistUpdate(state, MetadataResolveDelta(Nil, Nil, fullUpdate = false),
      metaDataState(1))
      .triggerPlaylistUpdate(state)
      .checkModelForPartialUpdates(Set.empty)
      .verifyNoMoreUpdatesOfTableHandler()

  it should "update the model when new metadata arrives" in:
    val metaData = playlistMetaData()
    val delta = MetadataResolveDelta(List(songDataIdx(1), songDataIdx(2), songDataIdx(3),
      songDataIdx(8)), List((0, 2), (7, 7)), fullUpdate = false)
    val SelectedIndex = 3
    val helper = new ControllerTestHelper

    helper.expectMetaDataUpdate(metaData, delta, metaDataState(1))
      .triggerMetaDataUpdate(metaData, selIdx = SelectedIndex)
      .checkModelForPartialUpdates(Set(0, 1, 2, 7))
      .verifyTableSelection(SelectedIndex)

  it should "notify the table handler about partial updates" in:
    val metaData = playlistMetaData()
    val updates = List((1, 3), (7, 7), (5, 6))
    val delta = MetadataResolveDelta(Nil, updates, fullUpdate = false)
    val helper = new ControllerTestHelper

    helper.expectMetaDataUpdate(metaData, delta, metaDataState(1))
      .triggerMetaDataUpdate(metaData)
    updates foreach (t => helper.verifyTableRangeUpdateNotification(t._1, t._2))
    helper.verifyNoMoreUpdatesOfTableHandler()

  it should "track the metadata state when processing updates" in:
    val delta1 = MetadataResolveDelta(songDataList(InitPlaylistSize).zipWithIndex, Nil,
      fullUpdate = true)
    val delta2 = MetadataResolveDelta(Nil, List((1, 2)), fullUpdate = false)
    val delta3 = MetadataResolveDelta(Nil, List((3, 5)), fullUpdate = false)
    val state = playerState()
    val metaData1 = playlistMetaData()
    val metaData2 = playlistMetaData()
    val helper = new ControllerTestHelper

    helper.expectPlaylistUpdate(state, delta1, metaDataState(1))
      .expectMetaDataUpdate(metaData1, delta2, metaDataState(2), metaDataState(1))
      .expectMetaDataUpdate(metaData2, delta3, metaDataState(3), metaDataState(2))
      .triggerPlaylistUpdate(state)
      .triggerMetaDataUpdate(metaData1)
      .triggerMetaDataUpdate(metaData2)
      .verifyTableDataChangeNotification()
      .verifyTableRangeUpdateNotification(1, 2)
      .verifyTableRangeUpdateNotification(3, 5)

  it should "clear the table selection if there is no current song" in:
    val state = playerState()
    val helper = new ControllerTestHelper

    helper.expectPlaylistUpdate(state, MetadataResolveDelta(Nil, Nil, fullUpdate = false),
      metaDataState(1))
      .triggerPlaylistUpdate(state)
      .verifyNoTableSelection()

  it should "set the table selection to the correct current index" in:
    val CurrentIndex = 3
    val state = playerState()
    val helper = new ControllerTestHelper

    helper.expectPlaylistUpdate(state, MetadataResolveDelta(Nil, Nil, fullUpdate = false),
      metaDataState(1))
      .expectCurrentIndex(state.playlist, CurrentIndex)
      .triggerPlaylistUpdate(state)
      .verifyTableSelection(CurrentIndex)

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper:
    /** Mock for the song data factory. */
    private val songDataFactory = mock[SongDataFactory]

    /** Mock for the metadata service. */
    private val service = createMetaDataService()

    /** Mock for the playlist service. */
    private val playlistService = createPlaylistService()

    /** The table model collection for the table. */
    private val tableModel = createModelCollection()

    /** The mock for the table handler. */
    private val tableHandler = createTableHandler()

    /** The controller instance to be tested. */
    private val controller = new PlaylistTableController(songDataFactory, service,
      playlistService, tableHandler)

    import scala.jdk.CollectionConverters._

    /**
      * Prepares the mock for the metadata service to process a playlist
      * update. The service returns the specified data.
      *
      * @param playerState  the ''AudioPlayerState''
      * @param delta        the ''MetaDataResolveDelta''
      * @param nextState    the updated metadata state
      * @param currentState the current metadata state
      * @return this test helper
      */
    def expectPlaylistUpdate(playerState: AudioPlayerState, delta: MetadataResolveDelta,
                             nextState: MetadataResolveState, currentState: MetadataResolveState
                             = InitMetaDataState): ControllerTestHelper =
      val resultFunc = serviceResult(delta, nextState)
      when(service.processPlaylistUpdate(playerState.playlist, playerState.playlistSeqNo,
        currentState)).thenReturn(resultFunc)
      this

    /**
      * Prepares the mock for the metadata service to process a metadata
      * update. The service returns the specified data.
      *
      * @param metaData     the updated metadata
      * @param delta        the ''MetaDataResolveDelta''
      * @param nextState    the updated metadata state
      * @param currentState the current metadata state
      * @return this test helper
      */
    def expectMetaDataUpdate(metaData: PlaylistMetadata, delta: MetadataResolveDelta,
                             nextState: MetadataResolveState,
                             currentState: MetadataResolveState = InitMetaDataState):
    ControllerTestHelper =
      val resultFunc = serviceResult(delta, nextState)
      when(service.processMetadataUpdate(metaData, currentState)).thenReturn(resultFunc)
      this

    /**
      * Invokes the test controller with the specified player state to check
      * whether state updates are handled correctly.
      *
      * @param playerState the player state
      * @return this test helper
      */
    def triggerPlaylistUpdate(playerState: AudioPlayerState): ControllerTestHelper =
      controller handlePlayerStateUpdate playerState
      this

    /**
      * Invokes the test controller with the specified metadata to check
      * whether this update is handled correctly. Also instructs the table
      * handler to return the given selected index.
      *
      * @param metaData the metadata
      * @param selIdx   the selected index in the table
      * @return this test helper
      */
    def triggerMetaDataUpdate(metaData: PlaylistMetadata, selIdx: Int = 0):
    ControllerTestHelper =
      when(tableHandler.getSelectedIndex).thenReturn(selIdx)
      controller handleMetaDataUpdate metaData
      this

    /**
      * Checks that the model collection has been updated to contain the
      * specified number of test songs (in order). This is used to test a full
      * update of the playlist.
      *
      * @param songCount the expected number of songs in the table model
      * @return this test helper
      */
    def checkModelForFullUpdate(songCount: Int): ControllerTestHelper =
      tableModel.asScala should contain theSameElementsInOrderAs songDataList(songCount)
      this

    /**
      * Checks that elements with the specified indices have been updated in
      * the table model collection. All other elements should still be in
      * initial state.
      *
      * @param updatedIndices a set with indices that should have been updated
      *                       (0-based)
      * @return this test helper
      */
    def checkModelForPartialUpdates(updatedIndices: Set[Int]): ControllerTestHelper =
      tableModel should have size InitPlaylistSize
      tableModel.asScala.zipWithIndex foreach { t =>
        val expSong = if updatedIndices contains t._2 then songData(t._2 + 1)
        else InitSongData
        t._1 should be(expSong)
      }
      this

    /**
      * Verifies that a notification about a full table data change has been
      * sent to the table handler.
      *
      * @return this test helper
      */
    def verifyTableDataChangeNotification(): ControllerTestHelper =
      verify(tableHandler).tableDataChanged()
      this

    /**
      * Verifies that the table handler was notified about a change in the
      * specified range of its data model.
      *
      * @param from the from index
      * @param to   the to index
      * @return this test helper
      */
    def verifyTableRangeUpdateNotification(from: Int, to: Int): ControllerTestHelper =
      verify(tableHandler).rowsUpdated(from, to)
      this

    /**
      * Checks that no more interactions took place with the table handler
      * mock.
      *
      * @return this test helper
      */
    def verifyNoMoreUpdatesOfTableHandler(): ControllerTestHelper =
      verify(tableHandler, Mockito.atMost(1)).getModel
      verify(tableHandler, Mockito.atMost(2)).getSelectedIndex
      verify(tableHandler, Mockito.atMost(1)).clearSelection()
      verify(tableHandler, Mockito.atMost(1)).setSelectedIndex(anyInt())
      verifyNoMoreInteractions(tableHandler)
      this

    /**
      * Generates a result function for the playlist metadata service. If the
      * function is passed the correct ''SongDataFactory'', it will return the
      * specified results.
      *
      * @param delta the metadata delta
      * @param state the updated metadata state
      * @return a result function for the metadata service
      */
    private def serviceResult(delta: MetadataResolveDelta, state: MetadataResolveState):
    SongDataFactory => (MetadataResolveDelta, MetadataResolveState) = factory => {
      factory should be(songDataFactory)
      (delta, state)
    }

    /**
      * Prepares the mock for the playlist service to return the specified
      * current index for the given playlist.
      *
      * @param pl  the playlist
      * @param idx the index to be returned for this playlist
      * @return this test helper
      */
    def expectCurrentIndex(pl: Playlist, idx: Int): ControllerTestHelper =
      when(playlistService.currentIndex(pl)).thenReturn(Some(idx))
      this

    /**
      * Verifies that the table selection was set to the specified index.
      *
      * @param idx the index
      * @return this test helper
      */
    def verifyTableSelection(idx: Int): ControllerTestHelper =
      verify(tableHandler).setSelectedIndex(idx)
      this

    /**
      * Verifies that the table selection was cleared.
      *
      * @return this test helper
      */
    def verifyNoTableSelection(): ControllerTestHelper =
      verify(tableHandler).clearSelection()
      this

    /**
      * Creates the mock for the metadata service and configures some default
      * behavior.
      *
      * @return the mock metadata service
      */
    private def createMetaDataService(): PlaylistMetadataService =
      val svc = mock[PlaylistMetadataService]
      when(svc.InitialState).thenReturn(InitMetaDataState)
      svc

    /**
      * Creates mock for the metadata service. This service is needed to find
      * the index of the current song. The mock is prepared to return no
      * current index.
      *
      * @return the mock for the playlist service
      */
    private def createPlaylistService(): PlaylistService[Playlist, MediaFileID] =
      val svc = mock[PlaylistService[Playlist, MediaFileID]]
      when(svc.currentIndex(any())).thenReturn(None)
      svc

    /**
      * Creates the collection serving as table model. It is already filled
      * with initial data.
      *
      * @return the table model collection
      */
    private def createModelCollection(): util.ArrayList[AnyRef] =
      val model = new util.ArrayList[AnyRef]
      model.addAll(Collections.nCopies(InitPlaylistSize, InitSongData))
      model

    /**
      * Creates the mock for the table handler.
      *
      * @return the mock table handler
      */
    private def createTableHandler(): TableHandler =
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(tableModel)
      handler

