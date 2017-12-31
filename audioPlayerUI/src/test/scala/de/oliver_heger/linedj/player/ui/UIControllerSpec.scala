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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.ActionTestHelper
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistMetaData, PlaylistMetaDataRegistration, PlaylistService}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.bus.ConsumerSupport.ConsumerRegistration
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourcePlaylistInfo, PlaybackProgressEvent}
import net.sf.jguiraffe.gui.builder.action.ActionStore
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.reflect.ClassTag

/**
  * Test class for ''UIController''.
  */
class UIControllerSpec extends FlatSpec with Matchers {
  /**
    * Generates a state object for the audio player.
    *
    * @param playing flag whether playback is active.
    * @return the player state
    */
  private def createState(playing: Boolean): AudioPlayerState =
    AudioPlayerState(Playlist(Nil, Nil), 1, playing, playlistClosed = true)

  /**
    * Creates a test playback progress event.
    *
    * @param pos  the position offset
    * @param time the time offset
    * @return the event
    */
  private def createProgressEvent(pos: Long, time: Long): PlaybackProgressEvent = {
    PlaybackProgressEvent(bytesProcessed = pos, playbackTime = time,
      currentSource = AudioSource("test", 123456, 0, 0))
  }

  "A UIController" should "pass a player state to its sub controllers" in {
    val state = createState(playing = false)
    val helper = new ControllerTestHelper

    helper.playerStateChanged(state)
      .verifyStatePassedToSubControllers(state)
  }

  it should "report the last player state" in {
    val state = createState(playing = false)
    val helper = new ControllerTestHelper

    helper.playerStateChanged(state)
      .verifyLastPlayerState(state)
  }

  it should "report an initial player state" in {
    val helper = new ControllerTestHelper

    val state = helper.lastPlayerState
    state.playbackActive shouldBe false
    state.playlist.playedSongs should have size 0
    state.playlist.pendingSongs should have size 0
  }

  it should "pass playlist meta data to the playlist table controller" in {
    val meta = PlaylistMetaData(Map.empty)
    val state = createState(playing = false)
    val optIndex = Some(42)
    val helper = new ControllerTestHelper

    helper.playerStateChanged(state)
      .expectCurrentSongIndex(state, optIndex)
      .metaDataChanged(meta)
      .verifyMetaDataPassedToSubController(meta, optIndex)
  }

  it should "pass a playlist progress event to the current song controller" in {
    val event = createProgressEvent(100, 200)
    val helper = new ControllerTestHelper

    helper.sendOnBus(event)
      .verifyProgressEventPassedToSubController(event)
  }

  it should "report the last progress event" in {
    val event = createProgressEvent(1, 2)
    val helper = new ControllerTestHelper

    helper.sendOnBus(event)
      .verifyLastProgressEvent(event)
  }

  it should "report an initial progress event" in {
    val helper = new ControllerTestHelper

    val event = helper.lastProgressEvent
    event.currentSource.length should be > 0L
    event.bytesProcessed should be(0)
    event.playbackTime should be(0)
  }

  it should "enable actions if playback is active" in {
    val helper = new ControllerTestHelper

    helper.playerStateChanged(createState(playing = true))
      .verifyActionsEnabled(UIController.ActionStopPlayback, UIController.ActionNextSong,
        UIController.ActionGotoSong, UIController.ActionPreviousSong)
  }

  it should "enable actions if playback is not active" in {
    val helper = new ControllerTestHelper

    helper.playerStateChanged(createState(playing = false))
      .verifyActionsEnabled(UIController.ActionStartPlayback)
  }

  it should "update action enabled states after each player state change" in {
    val helper = new ControllerTestHelper

    helper.playerStateChanged(createState(playing = true))
      .playerStateChanged(createState(playing = false))
      .verifyActionsEnabled(UIController.ActionStartPlayback)
  }

  it should "disable all actions after one has been triggered" in {
    val helper = new ControllerTestHelper

    helper.playerStateChanged(createState(playing = true))
      .playerActionTriggered()
      .verifyActionsEnabled()
  }

  it should "disable all actions if there is no current song" in {
    val state = createState(playing = false)
    val helper = new ControllerTestHelper

    helper.prepareEmptyPlaylist(state)
      .playerStateChanged(state)
      .verifyActionsEnabled()
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class ControllerTestHelper extends ActionTestHelper with MockitoSugar {
    /** Mock for the table controller. */
    private val tableController = mock[PlaylistTableController]

    /** Mock for the current song controller. */
    private val currentSongController = mock[CurrentSongController]

    /** Mock for the playlist service. */
    private val plService = createPlaylistService()

    /** A set with the names of all relevant actions. */
    private val actionNames = Set(UIController.ActionStartPlayback,
      UIController.ActionStopPlayback, UIController.ActionNextSong,
      UIController.ActionPreviousSong, UIController.ActionGotoSong)

    /** The controller to be tested. */
    private val controller = new UIController(mock[MessageBus], initActionMocks(), tableController,
      currentSongController, plService)

    /** The consumer function for player state change events. */
    private lazy val stateConsumer = findRegistration[AudioPlayerStateChangeRegistration].callback

    /** The consumer function for meta data updates. */
    private lazy val metaDataConsumer = findRegistration[PlaylistMetaDataRegistration].callback

    /**
      * Sends a notification about an update in the player state to the test
      * controller.
      *
      * @param state the state
      * @return this test helper
      */
    def playerStateChanged(state: AudioPlayerState): ControllerTestHelper = {
      val event = AudioPlayerStateChangedEvent(state)
      stateConsumer(event)
      this
    }

    /**
      * Verifies that the player state is passed to helper objects.
      *
      * @param state the state
      * @return this test helper
      */
    def verifyStatePassedToSubControllers(state: AudioPlayerState): ControllerTestHelper = {
      val io = Mockito.inOrder(tableController, currentSongController)
      io.verify(tableController).handlePlayerStateUpdate(state)
      io.verify(currentSongController).playlistStateChanged()
      this
    }

    /**
      * Returns the last player state received by the controller test
      * instance.
      *
      * @return the last player state
      */
    def lastPlayerState: AudioPlayerState = controller.lastPlayerState

    /**
      * Verifies that the last player state reported by the test controller
      * equals the passed in state.
      *
      * @param state the expected state
      * @return this test helper
      */
    def verifyLastPlayerState(state: AudioPlayerState): ControllerTestHelper = {
      lastPlayerState should be(state)
      this
    }

    /**
      * Passes the specified meta data to the corresponding consumer function.
      *
      * @param meta the meta data
      * @return this test helper
      */
    def metaDataChanged(meta: PlaylistMetaData): ControllerTestHelper = {
      metaDataConsumer(meta)
      this
    }

    /**
      * Verifies that the specified meta data has been passed to the correct
      * sub controllers.
      *
      * @param meta   the expected meta data
      * @param optIdx the optional index in the playlist
      * @return this test helper
      */
    def verifyMetaDataPassedToSubController(meta: PlaylistMetaData, optIdx: Option[Int]):
    ControllerTestHelper = {
      val io = Mockito.inOrder(tableController, currentSongController)
      io.verify(tableController).handleMetaDataUpdate(meta)
      io.verify(currentSongController).playlistDataChanged(optIdx)
      this
    }

    /**
      * Sends the specified message to the test controller via the message bus
      * receiver function.
      *
      * @param msg the message
      * @return this test helper
      */
    def sendOnBus(msg: AnyRef): ControllerTestHelper = {
      controller receive msg
      this
    }

    /**
      * Verifies that the specified event has been passed to the correct sub
      * controller.
      *
      * @param event the event
      * @return this test helper
      */
    def verifyProgressEventPassedToSubController(event: PlaybackProgressEvent):
    ControllerTestHelper = {
      verify(currentSongController).playlistProgress(event)
      this
    }

    /**
      * Returns the last progress event reported by the test controller.
      *
      * @return the last progress event
      */
    def lastProgressEvent: PlaybackProgressEvent = controller.lastProgressEvent

    /**
      * Verifies that the test controller reports the specified progress event
      * as the last received one.
      *
      * @param event the expected event
      * @return this test helper
      */
    def verifyLastProgressEvent(event: PlaybackProgressEvent): ControllerTestHelper = {
      lastProgressEvent should be(event)
      this
    }

    /**
      * Verifies that only the specified actions are enabled. All other
      * actions are expected to be disabled.
      *
      * @param actions the names of actions that should be enabled
      * @return this test helper
      */
    def verifyActionsEnabled(actions: String*): ControllerTestHelper = {
      actionNames foreach { a =>
        val expState = actions contains a
        isActionEnabled(a) shouldBe expState
      }
      this
    }

    /**
      * Notifies the test controller about an action that was triggered.
      *
      * @return this test helper
      */
    def playerActionTriggered(): ControllerTestHelper = {
      controller.playerActionTriggered()
      this
    }

    /**
      * Prepares the mock for the playlist service to report an undefined
      * current song for the playlist in the provided state object.
      *
      * @param state the state
      * @return this test helper
      */
    def prepareEmptyPlaylist(state: AudioPlayerState): ControllerTestHelper = {
      when(plService.currentSong(state.playlist)).thenReturn(None)
      this
    }

    /**
      * Prepares the mock for the playlist service to report the specified
      * current song index.
      *
      * @param state   the expected state
      * @param current the option for the current song
      * @return this test helper
      */
    def expectCurrentSongIndex(state: AudioPlayerState, current: Option[Int]):
    ControllerTestHelper = {
      when(plService.currentIndex(state.playlist)).thenReturn(current)
      this
    }

    /**
      * Creates all mock actions required by the test class and an action store
      * that provides access to them.
      *
      * @return the mock action store
      */
    private def initActionMocks(): ActionStore = {
      createActions(actionNames.toSeq: _*)
      resetActionStates()
      val store = createActionStore()
      doAnswer(new Answer[AnyRef] {
        override def answer(invocation: InvocationOnMock): AnyRef = {
          resetActionStates()
          null
        }
      }).when(store).enableGroup(UIController.PlayerActionGroup, false)
      store
    }

    /**
      * Creates an initialized mock for the playlist service. The mock is
      * prepared to return a current song for an arbitrary playlist. (This is
      * needed to test updates of action states; only if a playlist has a
      * current song, some actions can be enabled.)
      *
      * @return the mock playlist service
      */
    private def createPlaylistService(): PlaylistService = {
      val service = mock[PlaylistService]
      when(service.currentSong(any())).thenReturn(Some(mock[AudioSourcePlaylistInfo]))
      service
    }

    /**
      * Finds a registration of the specified type.
      *
      * @param t the class tag of the type
      * @tparam T the type of the registration
      * @return the consumer registration of this type
      */
    private def findRegistration[T <: ConsumerRegistration[_]](implicit t: ClassTag[T]): T =
      controller.registrations.find(_.getClass == t.runtimeClass) match {
        case None =>
          throw new AssertionError("No registration of type " + t.runtimeClass)
        case Some(reg) =>
          reg.id should be(controller.componentID)
          reg.asInstanceOf[T]
      }
  }

}