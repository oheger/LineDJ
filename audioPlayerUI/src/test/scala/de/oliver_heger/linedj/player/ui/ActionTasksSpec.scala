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

package de.oliver_heger.linedj.player.ui

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.player.ui.AudioPlayerConfig.AutoStartNever
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * Test class for the different action tasks of the audio player UI
  * application.
  */
class ActionTasksSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  /**
    * Creates a mock ''UIController'' that is initialized with some default
    * behavior.
    *
    * @param bus      the message bus
    * @param state    the last player state
    * @param progress the last progress event
    * @return the mock controller
    */
  private def createMockController(bus: MessageBus, state: AudioPlayerState,
                                   progress: PlaybackProgressEvent) = {
    val controller = mock[UIController]
    when(controller.messageBus).thenReturn(bus)
    when(controller.lastPlayerState).thenReturn(state)
    when(controller.lastProgressEvent).thenReturn(progress)
    controller
  }

  /**
    * Executes a test for an action task that produces a result on the UI
    * message bus. This method creates a pre-configured mock for an UI
    * controller and a task to be tested using the provided creator function.
    * The task is then executed, and the message published by it on the
    * message bus is captured.
    *
    * @param state       the last player state reported by the controller
    * @param progress    the last progress event reported by the controller
    * @param taskCreator the function to create the task
    * @param t           class tag for the expected message on the bus
    * @tparam T the type of the expected message on the bus
    * @return the result object published on the bus
    */
  private def checkTaskWithBusResult[T](state: AudioPlayerState = mock[AudioPlayerState],
                                        progress: PlaybackProgressEvent
                                        = mock[PlaybackProgressEvent])
                                       (taskCreator: UIController => PlayerActionTask)
                                       (implicit t: ClassTag[T]): T = {
    val bus = new MessageBusTestImpl
    val controller = createMockController(bus, state, progress)
    val task = taskCreator(controller)
    task.run()

    verify(controller).playerActionTriggered()
    bus.expectMessageType[T]
  }

  /**
    * Executes a test for an action task for which no result on the message bus
    * is expected. Works similar to ''checkTaskWithBusResult()'', but verifies
    * that no message is sent on the message bus.
    *
    * @param state       the last player state reported by the controller
    * @param progress    the last progress event reported by the controller
    * @param taskCreator the function to create the task
    */
  private def checkTaskNoBusResult(state: AudioPlayerState = mock[AudioPlayerState],
                                   progress: PlaybackProgressEvent
                                   = mock[PlaybackProgressEvent])
                                  (taskCreator: UIController => PlayerActionTask): Unit = {
    val bus = mock[MessageBus]
    val controller = createMockController(bus, state, progress)
    val task = taskCreator(controller)
    task.run()

    verifyNoInteractions(bus)
    verify(controller, never()).playerActionTriggered()
  }

  "A StartPlaybackTask" should "send a correct command on the message bus" in {
    val cmd = checkTaskWithBusResult[StartAudioPlayback]()(new StartPlaybackTask(_))
    cmd should be(StartAudioPlayback())
  }

  "A StopPlaybackTask" should "send a correct command on the message bus" in {
    val cmd = checkTaskWithBusResult[StopAudioPlayback]()(new StopPlaybackTask(_))
    cmd should be(StopAudioPlayback())
  }

  "A NextSongTask" should "send a correct command on the message bus" in {
    val cmd = checkTaskWithBusResult[AudioPlayerCommand]()(new NextSongTask(_))
    cmd should be(SkipCurrentSource)
  }

  /**
    * Checks a successful execution of the goto task.
    * @param closePlaylist flag whether the playlist is to be closed
    */
  private def checkGotoTask(closePlaylist: Boolean): Unit = {
    val Index = 11
    val playlist = mock[Playlist]
    val updatedPlaylist = mock[Playlist]
    val tabHandler = mock[TableHandler]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    when(tabHandler.getSelectedIndex).thenReturn(Index)
    when(plService.setCurrentSong(playlist, Index)).thenReturn(Some(updatedPlaylist))
    val state = AudioPlayerState(playlist, 1, playbackActive = true,
      playlistClosed = closePlaylist, playlistActivated = true)

    val cmd = checkTaskWithBusResult[SetPlaylist](state = state)(new GotoSongTask(_, tabHandler,
      plService))
    cmd should be(SetPlaylist(updatedPlaylist, closePlaylist))
  }

  "A GotoSongTask" should "change the current song in the playlist and close it" in {
    checkGotoTask(closePlaylist = true)
  }

  it should "change the current song in the playlist and keep it open" in {
    checkGotoTask(closePlaylist = false)
  }

  it should "not send a message if no updated playlist can be created" in {
    val Index = -1
    val playlist = mock[Playlist]
    val tabHandler = mock[TableHandler]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    when(tabHandler.getSelectedIndex).thenReturn(Index)
    when(plService.setCurrentSong(playlist, Index)).thenReturn(None)
    val state = AudioPlayerState(playlist, 1, playbackActive = true,
      playlistClosed = false, playlistActivated = true)

    checkTaskNoBusResult(state)(new GotoSongTask(_, tabHandler, plService))
  }

  /**
    * Creates a progress event with the given time offset.
    *
    * @param time the time offset (in seconds)
    * @return the progress event
    */
  private def createProgressEvent(time: Long): PlaybackProgressEvent =
    PlaybackProgressEvent(playbackTime = time.seconds, bytesProcessed = 1,
      currentSource = AudioSource("test", 20180105, 0, 0))

  "A PreviousSongTask" should "move to the previous song if progress is under threshold" in {
    val Config = AudioPlayerConfig(skipBackwardsThreshold = 10, maxUIFieldSize = 100,
      rotationSpeed = 1, autoStartMode = AutoStartNever)
    val Index = 17
    val playlist = mock[Playlist]
    val newPlaylist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    when(plService.currentIndex(playlist)).thenReturn(Some(Index))
    when(plService.setCurrentSong(playlist, Index - 1)).thenReturn(Some(newPlaylist))

    val cmd = checkTaskWithBusResult[SetPlaylist](state = AudioPlayerState(playlist, 1,
      playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1))(new PreviousSongTask(_,
      Config, plService))
    cmd should be(SetPlaylist(newPlaylist))
  }

  it should "reset the current song if progress is over the threshold" in {
    val Config = AudioPlayerConfig(skipBackwardsThreshold = 10, maxUIFieldSize = 100,
      rotationSpeed = 1, autoStartMode = AutoStartNever)
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]

    val cmd = checkTaskWithBusResult[SetPlaylist](state = AudioPlayerState(playlist, 1,
      playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold))(new PreviousSongTask(_,
      Config, plService))
    cmd should be(SetPlaylist(playlist))
    verifyNoInteractions(plService)
  }

  it should "respect the playlist closed flag" in {
    val Config = AudioPlayerConfig(skipBackwardsThreshold = 10, maxUIFieldSize = 100,
      rotationSpeed = 1, autoStartMode = AutoStartNever)
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]

    val cmd = checkTaskWithBusResult[SetPlaylist](state = AudioPlayerState(playlist, 1,
      playbackActive = true, playlistClosed = false, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold))(new PreviousSongTask(_,
      Config, plService))
    cmd should be(SetPlaylist(playlist, closePlaylist = false))
  }

  it should "reset the current song if it is the first in the playlist" in {
    val Config = AudioPlayerConfig(skipBackwardsThreshold = 10, maxUIFieldSize = 100,
      rotationSpeed = 1, autoStartMode = AutoStartNever)
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    when(plService.currentIndex(playlist)).thenReturn(Some(0))

    val cmd = checkTaskWithBusResult[SetPlaylist](state = AudioPlayerState(playlist, 1,
      playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1))(new PreviousSongTask(_,
      Config, plService))
    cmd should be(SetPlaylist(playlist))
  }

  it should "handle a None result in setCurrentSong" in {
    val Config = AudioPlayerConfig(skipBackwardsThreshold = 10, maxUIFieldSize = 100,
      rotationSpeed = 1, autoStartMode = AutoStartNever)
    val Index = 17
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    when(plService.currentIndex(playlist)).thenReturn(Some(Index))
    when(plService.setCurrentSong(playlist, Index - 1)).thenReturn(None)

    checkTaskNoBusResult(state = AudioPlayerState(playlist, 1,
      playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1))(new PreviousSongTask(_,
      Config, plService))
  }

  it should "handle a None result in currentIndex" in {
    val Config = AudioPlayerConfig(skipBackwardsThreshold = 10, maxUIFieldSize = 100,
      rotationSpeed = 1, autoStartMode = AutoStartNever)
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, MediaFileID]]
    when(plService.currentIndex(playlist)).thenReturn(None)

    checkTaskNoBusResult(state = AudioPlayerState(playlist, 1,
      playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1))(new PreviousSongTask(_,
      Config, plService))
  }
}
