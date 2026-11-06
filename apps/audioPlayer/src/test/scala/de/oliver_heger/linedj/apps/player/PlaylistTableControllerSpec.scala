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

import de.oliver_heger.linedj.apps.player.PlaylistTableControllerSpec.createUiState
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito.*
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util

object PlaylistTableControllerSpec:
  /**
    * Creates an [[AudioPlayerUIState]] object from the passed in parameters
    * using default values for irrelevant fields.
    *
    * @param playlist        the list of songs
    * @param optCurrentIndex the optional current index
    * @return the UI state object
    */
  def createUiState(playlist: List[SongData], optCurrentIndex: Option[Int]): AudioPlayerUIState =
    AudioPlayerUIState(
      playlist = playlist,
      optCurrentIndex = optCurrentIndex,
      optCurrentSong = None,
      playlistSeqNo = 0,
      idsToResolve = Nil,
      pendingResolveCnt = 0
    )
end PlaylistTableControllerSpec

/**
  * Test class for [[PlaylistTableController]].
  */
class PlaylistTableControllerSpec extends AnyFlatSpec, Matchers, MockitoSugar, OptionValues:

  import PlaylistTestHelper.*

  "A PlaylistTableController" should "update the table for a new UI state" in :
    val songs = songDataList(1, 16)()
    val CurrentIndex = 4
    val uiState = createUiState(songs, Some(CurrentIndex))
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(uiState)
      .verifyTableDataChangeNotification()
      .checkTableModel(songs)
      .verifyTableSelection(CurrentIndex)

  it should "not update the table if there are no changes in the UI state" in :
    val playerState = createPlayerState(extractIDs(songDataList(1, 8)()), 0, 41)
    val uiState1 = updateUiState(playerState)
    val uiState2 = uiState1.copy(idsToResolve = Nil, pendingResolveCnt = 10)
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(uiState1)
      .resetTableHandler()
      .triggerStateUpdate(uiState2)
      .verifyNoTableUpdate()

  it should "clear the table selection if there is no current song" in :
    val songs = songDataList(1, 12)()
    val uiState = createUiState(songs, None)
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(uiState)
      .verifyTableDataChangeNotification()
      .checkTableModel(songs)
      .verifyNoTableSelection()

  it should "set the table selection to the correct current index" in :
    val songs = songDataList(1, 10)()
    val CurrentIndex = 3
    val playerState = createPlayerState(extractIDs(songs), 2, 42)
    val uiState1 = updateUiState(playerState)
    val uiState2 = uiState1.copy(optCurrentIndex = Some(CurrentIndex))
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(uiState1)
      .resetTableHandler()
      .triggerStateUpdate(uiState2)
      .verifyTableSelection(CurrentIndex)
      .verifyTableDataChangeNotification(count = 0)

  it should "handle multiple state updates" in :
    val CurrentIndex = 5
    val songs1 = songDataList(1, 10)()
    val songs2 = songDataList(11, 20)()
    val uiState1 = createUiState(songs1, Some(CurrentIndex))
    val uiState2 = createUiState(songs2, Some(CurrentIndex))
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(uiState1)
      .resetTableHandler()
      .triggerStateUpdate(uiState2)
      .verifyTableSelection(CurrentIndex)
      .checkTableModel(songs2)

  /**
    * Test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper:
    /** The table model collection for the table. */
    private val tableModel = new util.ArrayList[AnyRef]

    /** The mock for the table handler. */
    private val tableHandler = createTableHandler()

    /** The controller instance to be tested. */
    private val controller = new PlaylistTableController(tableHandler)

    import scala.jdk.CollectionConverters.*

    /**
      * Invokes the test controller with the specified UI state to check
      * whether state updates are handled correctly.
      *
      * @param uiState the UI state
      * @return this test helper
      */
    def triggerStateUpdate(uiState: AudioPlayerUIState): ControllerTestHelper =
      controller.handleStateUpdate(uiState)
      this

    /**
      * Checks that the table model collection has been updated to contain the
      * expected songs.
      *
      * @param songs the songs expected in the table model
      * @return this test helper
      */
    def checkTableModel(songs: Iterable[SongData]): ControllerTestHelper =
      tableModel.asScala should contain theSameElementsInOrderAs songs
      this

    /**
      * Verifies that a notification about a full table data change has been
      * sent to the table handler.
      *
      * @param count the expected invocation count
      * @return this test helper
      */
    def verifyTableDataChangeNotification(count: Int = 1): ControllerTestHelper =
      verify(tableHandler, times(count)).tableDataChanged()
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
      * Checks that no interaction with the table handler was done.
      *
      * @return this test helper
      */
    def verifyNoTableUpdate(): ControllerTestHelper =
      verifyNoInteractions(tableHandler)
      this

    /**
      * Resets the mock for the table handler. This can be necessary in tests
      * with multiple state updates to do a proper verification.
      *
      * @return this test helper
      */
    def resetTableHandler(): ControllerTestHelper =
      reset(tableHandler)
      when(tableHandler.getModel).thenReturn(tableModel)
      this

    /**
      * Creates the mock for the table handler.
      *
      * @return the mock table handler
      */
    private def createTableHandler(): TableHandler =
      val handler = mock[TableHandler]
      when(handler.getModel).thenReturn(tableModel)
      handler
