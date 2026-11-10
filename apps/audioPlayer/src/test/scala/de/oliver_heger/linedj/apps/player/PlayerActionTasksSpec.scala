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

import de.oliver_heger.linedj.platform.MessageBusTestImpl
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState, PlaybackProgress}
import de.oliver_heger.linedj.platform.comm.MessageBus
import net.sf.jguiraffe.gui.builder.components.model.TableHandler
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

/**
  * Test class for the different action tasks of the audio player
  * application.
  */
class PlayerActionTasksSpec extends AnyFlatSpec, Matchers, MockitoSugar:
  /**
    * Creates a mock [[Controller]] that is initialized with some default
    * behavior.
    *
    * @param state    the last player state
    * @param progress the last progress event
    * @return the mock controller
    */
  private def createMockController(state: AudioPlayerState,
                                   progress: PlaybackProgress) =
    val controller = mock[Controller]
    when(controller.lastPlayerState).thenReturn(state)
    when(controller.lastProgressEvent).thenReturn(progress)
    controller

  /**
    * Executes a test for an action task that produces a result on the UI
    * message bus. This method creates a pre-configured mock for a
    * controller and a task to be tested using the provided creator function.
    * The task is then executed, and the message published by it on the
    * message bus is captured.
    *
    * @param state       the last player state reported by the controller
    * @param progress    the last progress event reported by the controller
    * @param taskCreator the function to create the task
    * @return the result object published on the bus
    */
  private def checkTaskWithBusResult(state: AudioPlayerState = mock[AudioPlayerState],
                                     progress: PlaybackProgress = mock[PlaybackProgress])
                                    (taskCreator: (Controller, MessageBus) => PlayerActionTask):
  AudioPlayerCommands =
    val bus = new MessageBusTestImpl
    val controller = createMockController(state, progress)
    val task = taskCreator(controller, bus)
    task.run()

    verify(controller).playerActionTriggered()
    bus.expectMessageType[AudioPlayerCommands]

  /**
    * Executes a test for an action task for which no result on the message bus
    * is expected. Works similar to _checkTaskWithBusResult()_, but verifies
    * that no message is sent on the message bus.
    *
    * @param state       the last player state reported by the controller
    * @param progress    the last progress event reported by the controller
    * @param taskCreator the function to create the task
    */
  private def checkTaskNoBusResult(state: AudioPlayerState = mock[AudioPlayerState],
                                   progress: PlaybackProgress = mock[PlaybackProgress])
                                  (taskCreator: (Controller, MessageBus) => PlayerActionTask): Unit =
    val bus = mock[MessageBus]
    val controller = createMockController(state, progress)
    val task = taskCreator(controller, bus)
    task.run()

    verifyNoInteractions(bus)
    verify(controller, never()).playerActionTriggered()

  "A StartPlaybackTask" should "send a correct command on the message bus" in :
    val cmd = checkTaskWithBusResult()(new StartPlaybackTask(_, _))

    cmd should be(AudioPlayerCommands.StartAudioPlayback)

  "A StopPlaybackTask" should "send a correct command on the message bus" in :
    val cmd = checkTaskWithBusResult()(new StopPlaybackTask(_, _))

    cmd should be(AudioPlayerCommands.StopAudioPlayback)

  "A NextSongTask" should "send a correct command on the message bus" in :
    val cmd = checkTaskWithBusResult()(new NextSongTask(_, _))

    cmd should be(AudioPlayerCommands.SkipCurrentSource)

  /**
    * Checks a successful execution of the goto task.
    *
    * @param closePlaylist flag whether the playlist is to be closed
    */
  private def checkGotoTask(closePlaylist: Boolean): Unit =
    val Index = 11
    val playlist = mock[Playlist]
    val updatedPlaylist = mock[Playlist]
    val tabHandler = mock[TableHandler]
    val plService = mock[PlaylistService[Playlist, String]]
    when(tabHandler.getSelectedIndex).thenReturn(Index)
    when(plService.setCurrentSong(playlist, Index)).thenReturn(Some(updatedPlaylist))
    val state = AudioPlayerState(
      playlist,
      1,
      playbackActive = true,
      playlistClosed = closePlaylist,
      playlistActivated = true
    )

    val cmd = checkTaskWithBusResult(state = state)(new GotoSongTask(_, _, tabHandler, plService))

    cmd should be(AudioPlayerCommands.SetPlaylist(updatedPlaylist, closePlaylist))

  "A GotoSongTask" should "change the current song in the playlist and close it" in :
    checkGotoTask(closePlaylist = true)

  it should "change the current song in the playlist and keep it open" in :
    checkGotoTask(closePlaylist = false)

  it should "not send a message if no updated playlist can be created" in :
    val Index = -1
    val playlist = mock[Playlist]
    val tabHandler = mock[TableHandler]
    val plService = mock[PlaylistService[Playlist, String]]
    when(tabHandler.getSelectedIndex).thenReturn(Index)
    when(plService.setCurrentSong(playlist, Index)).thenReturn(None)
    val state = AudioPlayerState(playlist, 1, playbackActive = true,
      playlistClosed = false, playlistActivated = true)

    checkTaskNoBusResult(state)(new GotoSongTask(_, _, tabHandler, plService))

  /**
    * Creates a progress event with the given time offset.
    *
    * @param time the time offset
    * @return the progress event
    */
  private def createProgressEvent(time: FiniteDuration): PlaybackProgress =
    PlaybackProgress(playbackTime = time, bytesProcessed = 1)

  "A PreviousSongTask" should "move to the previous song if progress is under threshold" in :
    val Config = AudioPlayerConfig(
      skipBackwardsThreshold = 10.seconds,
      maxUIFieldSize = 100,
      rotationSpeed = 1,
      autoStartMode = AudioPlayerConfig.AutoStartNever
    )
    val Index = 17
    val playlist = mock[Playlist]
    val newPlaylist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, String]]
    when(plService.currentIndex(playlist)).thenReturn(Some(Index))
    when(plService.setCurrentSong(playlist, Index - 1)).thenReturn(Some(newPlaylist))

    val cmd = checkTaskWithBusResult(
      state = AudioPlayerState(playlist, 1, playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1.second)
    )(new PreviousSongTask(_, _, Config, plService))

    cmd should be(AudioPlayerCommands.SetPlaylist(newPlaylist))

  it should "reset the current song if progress is over the threshold" in :
    val Config = AudioPlayerConfig(
      skipBackwardsThreshold = 10.seconds,
      maxUIFieldSize = 100,
      rotationSpeed = 1,
      autoStartMode = AudioPlayerConfig.AutoStartNever
    )
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, String]]

    val cmd = checkTaskWithBusResult(
      state = AudioPlayerState(playlist, 1, playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold)
    )(new PreviousSongTask(_, _, Config, plService))

    cmd should be(AudioPlayerCommands.SetPlaylist(playlist))
    verifyNoInteractions(plService)

  it should "respect the playlist closed flag" in :
    val Config = AudioPlayerConfig(
      skipBackwardsThreshold = 10.seconds,
      maxUIFieldSize = 100,
      rotationSpeed = 1,
      autoStartMode = AudioPlayerConfig.AutoStartNever
    )
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, String]]

    val cmd = checkTaskWithBusResult(
      state = AudioPlayerState(playlist, 1, playbackActive = true, playlistClosed = false, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold)
    )(new PreviousSongTask(_, _, Config, plService))

    cmd should be(AudioPlayerCommands.SetPlaylist(playlist, closePlaylist = false))

  it should "reset the current song if it is the first in the playlist" in :
    val Config = AudioPlayerConfig(
      skipBackwardsThreshold = 10.seconds,
      maxUIFieldSize = 100,
      rotationSpeed = 1,
      autoStartMode = AudioPlayerConfig.AutoStartNever
    )
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, String]]
    when(plService.currentIndex(playlist)).thenReturn(Some(0))

    val cmd = checkTaskWithBusResult(
      state = AudioPlayerState(playlist, 1, playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1.second)
    )(new PreviousSongTask(_, _, Config, plService))

    cmd should be(AudioPlayerCommands.SetPlaylist(playlist))

  it should "handle a None result in setCurrentSong" in :
    val Config = AudioPlayerConfig(
      skipBackwardsThreshold = 10.seconds,
      maxUIFieldSize = 100,
      rotationSpeed = 1,
      autoStartMode = AudioPlayerConfig.AutoStartNever
    )
    val Index = 17
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, String]]
    when(plService.currentIndex(playlist)).thenReturn(Some(Index))
    when(plService.setCurrentSong(playlist, Index - 1)).thenReturn(None)

    checkTaskNoBusResult(
      state = AudioPlayerState(playlist, 1, playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1.second)
    )(new PreviousSongTask(_, _, Config, plService))

  it should "handle a None result in currentIndex" in :
    val Config = AudioPlayerConfig(
      skipBackwardsThreshold = 10.seconds,
      maxUIFieldSize = 100,
      rotationSpeed = 1,
      autoStartMode = AudioPlayerConfig.AutoStartNever
    )
    val playlist = mock[Playlist]
    val plService = mock[PlaylistService[Playlist, String]]
    when(plService.currentIndex(playlist)).thenReturn(None)

    checkTaskNoBusResult(
      state = AudioPlayerState(playlist, 1, playbackActive = true, playlistClosed = true, playlistActivated = true),
      progress = createProgressEvent(Config.skipBackwardsThreshold - 1.second)
    )(new PreviousSongTask(_, _, Config, plService))
