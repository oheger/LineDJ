/*
 * Copyright 2015-2018 The Developers Team.
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

import akka.actor.Actor.Receive
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistMetaData, PlaylistMetaDataRegistration, PlaylistService}
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, AudioPlayerStateChangeRegistration, AudioPlayerStateChangedEvent}
import de.oliver_heger.linedj.platform.bus.{ConsumerSupport, Identifiable}
import de.oliver_heger.linedj.platform.comm.{MessageBus, MessageBusListener}
import de.oliver_heger.linedj.platform.mediaifc.ext.MediaIfcExtension.ConsumerRegistrationProvider
import de.oliver_heger.linedj.player.engine.{AudioSource, PlaybackProgressEvent}
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import net.sf.jguiraffe.gui.builder.action.ActionStore

object UIController {
  /** Name of the group that contains all player-related actions. */
  val PlayerActionGroup = "PlayerActions"

  /** Name of the action to start playback. */
  val ActionStartPlayback = "startPlaybackAction"

  /** Name of the action to stop playback. */
  val ActionStopPlayback = "stopPlaybackAction"

  /** Name of the action to move to the next song. */
  val ActionNextSong = "nextSongAction"

  /** Name of the action to move to the previous song. */
  val ActionPreviousSong = "previousSongAction"

  /** Name of the action to move to a specific song. */
  val ActionGotoSong = "gotoSongAction"

  /**
    * Initial value for the last progress event. Here some plausible values are
    * defined. This is overridden as soon as a real progress event is received.
    */
  private val InitialProgressEvent =
    PlaybackProgressEvent(bytesProcessed = 0, playbackTime = 0,
      currentSource = AudioSource("", 1, 0, 0))
}

/**
  * The main controller class for the audio player application.
  *
  * This class is responsible for controlling the main window of the audio
  * player application. It manages all required registrations at the message
  * bus and for consumers and is the top-level entry point for event
  * processing. It also handles the enabled states of actions affecting the
  * audio player.
  *
  * In order to update the UI, the class relies on some helper classes. Actions
  * themselves are also handled by specialized action task classes. However,
  * the latter make use of some data maintained by this class.
  *
  * @param messageBus              the UI message bus
  * @param actionStore             the action store
  * @param playlistTableController the table controller
  * @param currentSongController   the current song controller
  * @param plService               the playlist service
  * @param config                  the configuration for the application
  */
class UIController(val messageBus: MessageBus, actionStore: ActionStore,
                   playlistTableController: PlaylistTableController,
                   currentSongController: CurrentSongController,
                   plService: PlaylistService[Playlist, MediaFileID],
                   config: AudioPlayerConfig) extends MessageBusListener
  with ConsumerRegistrationProvider with Identifiable {

  import UIController._

  /**
    * @inheritdoc This class is a consumer for playlist change events and
    *             updates of playlist meta data.
    */
  override val registrations: Iterable[ConsumerSupport.ConsumerRegistration[_]] =
    List(AudioPlayerStateChangeRegistration(componentID, consumePlayerStateChangeEvent),
      PlaylistMetaDataRegistration(componentID, consumePlaylistMetaDataChanged))

  /** Stores the last known player state. */
  private var currentState = AudioPlayerState.Initial

  /** Stores the last playback progress event. */
  private var currentProgress = InitialProgressEvent

  /**
    * The message bus receiver function. It mainly receives events about
    * playback progress.
    *
    * @return the message handling function
    */
  override def receive: Receive = {
    case event: PlaybackProgressEvent =>
      currentSongController playlistProgress event
      currentProgress = event
  }

  /**
    * Returns the latest player state received by this object.
    *
    * @return the latest player state
    */
  def lastPlayerState: AudioPlayerState = currentState

  /**
    * Returns the last ''PlaybackProgressEvent'' received by this controller.
    *
    * @return the last ''PlaybackProgressEvent''
    */
  def lastProgressEvent: PlaybackProgressEvent = currentProgress

  /**
    * Notifies this controller that a player-related action has been executed.
    * This causes all actions to be disabled until the updated player state
    * is received. Based on this state, the enabled state of actions is set.
    */
  def playerActionTriggered(): Unit = {
    actionStore.enableGroup(PlayerActionGroup, false)
  }

  /**
    * Consumer function for notifications of changes of the audio player state.
    *
    * @param event the state change event
    */
  private def consumePlayerStateChangeEvent(event: AudioPlayerStateChangedEvent): Unit = {
    playlistTableController handlePlayerStateUpdate event.state
    currentSongController.playlistStateChanged()
    updateActionStates(event.state)
    handlePlaybackAutoStart(currentState, event.state)
    currentState = event.state
  }

  /**
    * Consumer function for updates of playlist meta data.
    *
    * @param meta the updated meta data
    */
  private def consumePlaylistMetaDataChanged(meta: PlaylistMetaData): Unit = {
    playlistTableController handleMetaDataUpdate meta
    currentSongController.playlistDataChanged(plService currentIndex currentState.playlist)
  }

  /**
    * Updates the enabled states of all player-relevant actions after a change
    * in the state of the audio player.
    *
    * @param state the current state of the audio player
    */
  private def updateActionStates(state: AudioPlayerState): Unit = {
    val hasCurrent = plService.currentSong(state.playlist).isDefined
    val isPlaying = hasCurrent && state.playbackActive
    enableAction(ActionStopPlayback, isPlaying)
    enableAction(ActionPreviousSong, isPlaying)
    enableAction(ActionNextSong, isPlaying)
    enableAction(ActionGotoSong, plService.size(state.playlist) > 0)
    enableAction(ActionStartPlayback, hasCurrent && !state.playbackActive)
  }

  /**
    * Enables the specified action.
    *
    * @param name  the name of the action
    * @param state the new enabled state
    */
  private def enableAction(name: String, state: Boolean): Unit = {
    actionStore.getAction(name) setEnabled state
  }

  /**
    * Checks whether playback needs to be started when reacting on a playlist
    * change event.
    *
    * @param oldState the old state of the audio player
    * @param newState the new state of the audio player
    */
  private def handlePlaybackAutoStart(oldState: AudioPlayerState,
                                      newState: AudioPlayerState): Unit = {
    if (!newState.playbackActive &&
      oldState.playlistSeqNo != newState.playlistSeqNo &&
      newState.playlist.pendingSongs.nonEmpty &&
      config.autoStartMode.canStartPlayback(newState)) {
      actionStore.getAction(ActionStartPlayback).execute(null)
    }
  }
}
