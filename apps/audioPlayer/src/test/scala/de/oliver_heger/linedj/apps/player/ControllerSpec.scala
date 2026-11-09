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

import de.oliver_heger.linedj.apps.player.AudioPlayerConfig.AutoStartAlways
import de.oliver_heger.linedj.apps.player.PlaylistTestHelper.*
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.platform.audio2.impl.PlaylistServiceImpl
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerState, PlaybackProgress}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.Inspectors.forEvery
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

object ControllerSpec:
  /** A set with the names of all relevant actions. */
  private val ActionNames = Set(
    Controller.ActionStartPlayback,
    Controller.ActionStopPlayback,
    Controller.ActionNextSong,
    Controller.ActionPreviousSong,
    Controller.ActionGotoSong
  )

  /** Type alias for the callback function used by the resolver service. */
  private type ResolverCallback = Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit

  /** A list with test songs. */
  private val TestSongList = PlaylistTestHelper.songDataList(1, 8)()

  /** Stores the file IDs of the test song list. */
  private val TestFileIDs = extractIDs(TestSongList)

  /** A test playlist used by the default audio player state. */
  private val TestPlaylist = Playlist(pendingSongs = TestFileIDs, Nil)

  /**
    * Generates a state object for the audio player with the given parameters
    * and meaningful default values.
    *
    * @param playing  flag whether playback is active.
    * @param playlist an optional playlist
    * @param closed   flag whether the playlist has been closed
    * @param active   flag if the playlist has been activated
    * @param seqNo    the sequence number of the playlist
    * @return the player state
    */
  private def createPlayerState(playing: Boolean = false,
                                playlist: Playlist = TestPlaylist,
                                closed: Boolean = true,
                                active: Boolean = true,
                                seqNo: Int = 1): AudioPlayerState =
    AudioPlayerState(playlist, seqNo, playing, playlistClosed = closed, playlistActivated = active)
end ControllerSpec

/**
  * Test class for [[ControllerSpec]].
  */
class ControllerSpec extends AnyFlatSpec, Matchers, OptionValues:

  import ControllerSpec.*

  "A Controller" should "pass updated UI state to the sub controllers" in :
    val playerState = createPlayerState()
    val expectedUiState = updateUiState(playerState)
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(playerState)
      .verifyUiStateUpdate(expectedUiState)

  it should "manage the UI state over multiple updates" in :
    val newPlaylist = songDataList(100, 120)()
    val newResolveIDs = extractIDs(newPlaylist)
    val playerState1 = createPlayerState()
    val playerState2 = createPlayerState(playlist = PlaylistServiceImpl.toPlaylist(extractIDs(newPlaylist), 2).value)
    val uiState1 = updateUiState(playerState1)
    val expectedUiState = updateUiState(playerState2, uiState1)
    val helper = new ControllerTestHelper

    helper.expectResolveOperation(newResolveIDs)
      .triggerStateUpdate(playerState1)
      .triggerStateUpdate(playerState2)
      .verifyUiStateUpdate(expectedUiState)

  it should "handle a successful resolve operation" in :
    val playerState = createPlayerState()
    val expectedUiState = updateUiState(playerState).copy(
      playlist = TestSongList,
      idsToResolve = Nil,
      pendingResolveCnt = 0,
      optCurrentSong = TestSongList.headOption
    )
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(playerState)
      .invokeResolverCallback(TestFileIDs, TestSongList)
      .verifyUiStateUpdate(expectedUiState)

  it should "handle a failed resolve operation" in :
    val playerState = createPlayerState()
    val expectedUiState1 = updateUiState(playerState)
    val expectedUiState2 = expectedUiState1.copy(idsToResolve = Nil, pendingResolveCnt = 0)
    val exception = new IllegalStateException("Test exception: Failed to resolve media files.")
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(playerState)
      .verifyUiStateUpdate(expectedUiState1)
      .invokeResolverCallback(TestFileIDs, Failure(exception))
      .verifyUiStateUpdate(expectedUiState2)

  it should "ignore player state updates that do not change the UI state" in :
    val playerState = createPlayerState()
    val expectedUiState = updateUiState(playerState)
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(playerState)
      .verifyUiStateUpdate(expectedUiState)
      .triggerStateUpdate(playerState)
      .verifyNoMoreUiStateUpdate()

  it should "pass a playback progress event to the current song controller" in :
    val helper = new ControllerTestHelper

    helper.testPlaybackProgressEvent()

  it should "enable actions if playback is active" in :
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(createPlayerState(playing = true))
      .verifyActionsEnabled(
        Controller.ActionStopPlayback,
        Controller.ActionNextSong,
        Controller.ActionGotoSong,
        Controller.ActionPreviousSong
      )

  it should "enable actions if playback is not active" in :
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(createPlayerState())
      .verifyActionsEnabled(Controller.ActionStartPlayback, Controller.ActionGotoSong)

  it should "disable all actions after one has been triggered" in :
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(createPlayerState(playing = true))
      .playerActionTriggered()
      .verifyActionsEnabled()

  it should "disable actions if there is no current song" in :
    val playerState = createPlayerState(playlist = Playlist(Nil, TestFileIDs))
    val helper = new ControllerTestHelper

    helper.expectResolveOperation(TestFileIDs.reverse)
      .triggerStateUpdate(playerState)
      .verifyActionsEnabled(Controller.ActionGotoSong)

  it should "disable actions if the playlist is empty" in :
    val playerState = createPlayerState(playlist = Playlist(Nil, Nil))
    val helper = new ControllerTestHelper

    helper.triggerStateUpdate(playerState)
      .verifyActionsEnabled()

  it should "start playback for a new playlist if configured" in :
    val helper = new ControllerTestHelper
    val state = createPlayerState()

    helper.enablePlaybackAutoStart(AudioPlayerConfig.AutoStartAlways)
      .triggerStateUpdate(state)
      .verifyPlaybackStarted()

  it should "not start playback for a new playlist if not configured" in :
    val helper = new ControllerTestHelper
    val state = createPlayerState()

    helper.triggerStateUpdate(state)
      .verifyPlaybackNotStarted()

  it should "not start playback if the new playlist is empty" in :
    val helper = new ControllerTestHelper
    val state = createPlayerState(playlist = Playlist(Nil, Nil))

    helper.enablePlaybackAutoStart(AudioPlayerConfig.AutoStartAlways)
      .triggerStateUpdate(state)
      .verifyPlaybackNotStarted()

  it should "not start playback if the playlist sequence number does not change" in :
    val helper = new ControllerTestHelper
    val state1 = createPlayerState(active = false, closed = false)
    val state2 = state1.copy(playlistClosed = true)

    helper.enablePlaybackAutoStart(AudioPlayerConfig.AutoStartIfClosed)
      .triggerStateUpdate(state1)
      .triggerStateUpdate(state2)
      .verifyPlaybackNotStarted()

  it should "start playback if the playlist is now activated" in :
    val helper = new ControllerTestHelper
    val state1 = createPlayerState(active = false, closed = false)
    val state2 = state1.copy(playlistActivated = true, playlistClosed = true)

    helper.enablePlaybackAutoStart(AudioPlayerConfig.AutoStartIfClosed)
      .triggerStateUpdate(state1)
      .triggerStateUpdate(state2)
      .verifyPlaybackStarted()

  it should "not start playback for a new playlist if it is already active" in :
    val state = createPlayerState(playing = true)
    val helper = new ControllerTestHelper

    helper.enablePlaybackAutoStart(AutoStartAlways)
      .triggerStateUpdate(state)
      .verifyPlaybackNotStarted()

  it should "not start playback for mode if-closed if the playlist is not closed" in :
    val state = createPlayerState(closed = false)
    val helper = new ControllerTestHelper

    helper.enablePlaybackAutoStart(AudioPlayerConfig.AutoStartIfClosed)
      .triggerStateUpdate(state)
      .verifyPlaybackNotStarted()

  it should "start playback for mode if-closed if the playlist is closed" in :
    val state = createPlayerState()
    val helper = new ControllerTestHelper

    helper.enablePlaybackAutoStart(AudioPlayerConfig.AutoStartIfClosed)
      .triggerStateUpdate(state)
      .verifyPlaybackStarted()

  it should "report an initial audio player state" in :
    val helper = new ControllerTestHelper

    helper.lastPlayerState should be(AudioPlayerState.Initial)

  it should "report the last received audio player state" in :
    val state = createPlayerState()
    val helper = new ControllerTestHelper

    val lastState = helper.triggerStateUpdate(state).lastPlayerState

    lastState should be(state)

  it should "report an initial PlaybackProgress event" in :
    val helper = new ControllerTestHelper

    helper.lastProgressEvent should be(PlaybackProgress(0, 0.seconds))

  it should "report the last received PlaybackProgress event" in :
    val progress = PlaybackProgress(10000, 90.seconds)
    val helper = new ControllerTestHelper

    val lastProgress = helper.sendProgressEvent(progress).lastProgressEvent

    lastProgress should be(progress)

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper extends ActionTestHelper, MockitoSugar:
    /** Mock for the table controller. */
    private val tableController = mock[PlaylistTableController]

    /** Mock for the current song controller. */
    private val currentSongController = mock[CurrentSongController]

    /** Mock for the configuration of the application. */
    private val playerConfig = createAppConfig()

    /** The controller under test. */
    private val controller = createController()

    /**
      * A set that is populated with the IDs of media files for which separate
      * invocations of the resolver service are expected.
      */
    private var fileIDsToResolve: Set[Iterable[String]] = Set(TestFileIDs)

    /**
      * A map that holds the callbacks passed to invocations of the resolver
      * service. This is used pass results to the callbacks later.
      */
    private var resolverCallbacks = Map.empty[Iterable[String], ResolverCallback]

    /**
      * Adds the given IDs to the expectations of the file resolver service.
      * This means that a resolve operation on these IDs is going to happen.
      *
      * @param fileIDs the file IDs
      * @return this test helper
      */
    def expectResolveOperation(fileIDs: Iterable[String]): ControllerTestHelper =
      fileIDsToResolve += fileIDs
      this

    /**
      * Sends the given update of the player state via the message bus
      * interface to the test controller.
      *
      * @param playerState the audio player state
      * @return this test helper
      */
    def triggerStateUpdate(playerState: AudioPlayerState): ControllerTestHelper =
      controller.receive(playerState)
      this

    /**
      * Sends the given [[PlaybackProgress]] event via the message bus
      * interface to the test controller.
      *
      * @param progress the progress event
      * @return this test helper
      */
    def sendProgressEvent(progress: PlaybackProgress): ControllerTestHelper =
      controller.receive(progress)
      this

    /**
      * Passes the given result of a resolver operation to the callback
      * registered by the invocation of the resolver service for the given file
      * IDs. This fails if the fileIDs have not been resolved.
      *
      * @param fileIDs the file IDs to be resolved
      * @param result  the result to pass to the callback
      * @return this test helper
      */
    def invokeResolverCallback(fileIDs: Iterable[String],
                               result: Try[Map[String, ArchiveModel.MediaFileInfo]]): ControllerTestHelper =
      val callback = resolverCallbacks.get(fileIDs).value
      callback(result)
      this

    /**
      * Creates a success result for a resolver operation from the provided
      * list of songs and passes it to the callback registered by the
      * invocation of the resolver service for the given file IDs. Works
      * analogously to the overloaded method.
      *
      * @param fileIDs       the file IDs to be resolved
      * @param resolvedSongs the songs resolved by the operation
      * @return this test helper
      */
    def invokeResolverCallback(fileIDs: Iterable[String], resolvedSongs: List[SongData]): ControllerTestHelper =
      val fileInfoMap = resolvedSongs.map: song =>
        song.fileInfo.metadata.checksum -> song.fileInfo
      .toMap
      invokeResolverCallback(fileIDs, Success(fileInfoMap))

    /**
      * Notifies the test controller about an action that was triggered.
      *
      * @return this test helper
      */
    def playerActionTriggered(): ControllerTestHelper =
      controller.playerActionTriggered()
      this

    /**
      * Verifies that the given state was propagated to the helper controllers.
      *
      * @param state the state
      * @return this test helper
      */
    def verifyUiStateUpdate(state: AudioPlayerUIState): ControllerTestHelper =
      verify(tableController).handleStateUpdate(state)
      verify(currentSongController).handleStateUpdate(state)
      this

    /**
      * Verifies that no further state updates are propagated to the helper
      * controllers.
      *
      * @return this test helper
      */
    def verifyNoMoreUiStateUpdate(): ControllerTestHelper =
      verifyNoMoreInteractions(tableController, currentSongController)
      this

    /**
      * Tests whether a playback progress event notification is correctly
      * passed to the current song controller.
      *
      * @return this test helper
      */
    def testPlaybackProgressEvent(): ControllerTestHelper =
      val progress = PlaybackProgress(10000, 10.seconds)
      sendProgressEvent(progress)

      verify(currentSongController).playlistProgress(progress)
      this

    /**
      * Verifies that only the specified actions are enabled. All other
      * actions are expected to be disabled.
      *
      * @param actions the names of actions that should be enabled
      * @return this test helper
      */
    def verifyActionsEnabled(actions: String*): ControllerTestHelper =
      val actionSet = actions.toSet
      forEvery(ActionNames): action =>
        val expState = actions.contains(action)
        isActionEnabled(action) shouldBe expState
      this

    /**
      * Prepares the mock for the player configuration to return the specified
      * auto start mode.
      *
      * @param mode the auto start mode
      * @return this test helper
      */
    def enablePlaybackAutoStart(mode: AudioPlayerConfig.AutoStartMode): ControllerTestHelper =
      when(playerConfig.autoStartMode).thenReturn(mode)
      this

    /**
      * Verifies that the action to start playback has been triggered once.
      *
      * @return this test helper
      */
    def verifyPlaybackStarted(): ControllerTestHelper =
      verify(actions(Controller.ActionStartPlayback)).execute(null)
      this

    /**
      * Verifies that the action to start playback has not been triggered.
      *
      * @return this test helper
      */
    def verifyPlaybackNotStarted(): ControllerTestHelper =
      verify(actions(Controller.ActionStartPlayback), never()).execute(any())
      this

    /**
      * Returns the last [[AudioPlayerState]] from the test controller.
      *
      * @return the last player state returned by the test controller
      */
    def lastPlayerState: AudioPlayerState = controller.lastPlayerState

    /**
      * Returns the last [[PlaybackProgress]] event from the test controller.
      *
      * @return the last progress event returned by the test controller
      */
    def lastProgressEvent: PlaybackProgress = controller.lastProgressEvent

    /**
      * Creates all mock actions required by the test class and an action store
      * that provides access to them.
      *
      * @return the mock action store
      */
    private def initActionMocks(): ActionStore =
      createActions(ActionNames.toSeq: _*)
      resetActionStates()
      val store = createActionStore()
      doAnswer((_: InvocationOnMock) =>
        resetActionStates()
        null
      ).when(store).enableGroup(Controller.PlayerActionGroup, false)
      store

    /**
      * Creates a mock for the configuration of the player application.
      *
      * @return the mock for the configuration
      */
    private def createAppConfig(): AudioPlayerConfig =
      val config = mock[AudioPlayerConfig]
      when(config.autoStartMode).thenReturn(AudioPlayerConfig.AutoStartNever)
      config

    /**
      * Creates a stub resolver service implementation whose behavior can be
      * controlled by this test helper class.
      *
      * @return the stub resolver service
      */
    private def createResolverService(): MediaFileInfoResolverService =
      new MediaFileInfoResolverService:
        override def resolveFileIDs(ids: Iterable[String])
                                   (callback: Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit): Unit =
          fileIDsToResolve should contain(ids)
          resolverCallbacks += ids -> callback

    /**
      * Creates the controller to be tested.
      *
      * @return the test controller instance
      */
    private def createController(): Controller =
      new Controller(
        initActionMocks(),
        tableController,
        currentSongController,
        PlaylistServiceImpl,
        createResolverService(),
        playerConfig
      )
