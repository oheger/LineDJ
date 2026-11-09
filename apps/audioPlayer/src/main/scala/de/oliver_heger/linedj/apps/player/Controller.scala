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
import de.oliver_heger.linedj.platform.audio2.playlist.{MediaFileInfoResolverService, Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerState, PlaybackProgress}
import de.oliver_heger.linedj.platform.comm.MessageBusListener
import net.sf.jguiraffe.gui.builder.action.ActionStore
import org.apache.logging.log4j.LogManager
import org.apache.pekko.actor.Actor.Receive

import scala.concurrent.duration.DurationInt
import scala.util.Try

object Controller:
  /** Name of the group that contains all player-related actions. */
  final val PlayerActionGroup = "PlayerActions"

  /** Name of the action to start playback. */
  final val ActionStartPlayback = "startPlaybackAction"

  /** Name of the action to stop playback. */
  final val ActionStopPlayback = "stopPlaybackAction"

  /** Name of the action to move to the next song. */
  final val ActionNextSong = "nextSongAction"

  /** Name of the action to move to the previous song. */
  final val ActionPreviousSong = "previousSongAction"

  /** Name of the action to move to a specific song. */
  final val ActionGotoSong = "gotoSongAction"

  /** The logger. */
  private val Log = LogManager.getLogger(classOf[Controller])

  /**
    * Checks if there is a relevant change in the state of the current
    * playlist. This is used to determine whether auto start of playback can be
    * triggered.
    *
    * @param oldState the old audio player state
    * @param newState the new audio player state
    * @return a flag whether there is a relevant state change
    */
  private def playlistStateChanged(oldState: AudioPlayerState, newState: AudioPlayerState): Boolean =
    oldState.playlistSeqNo != newState.playlistSeqNo ||
      (!oldState.playlistActivated && newState.playlistActivated)
end Controller

/**
  * The main controller class for the audio player application.
  *
  * The class is responsible for managing and updating the current application
  * state. To do this, it listens on the system message bus and reacts on 
  * relevant events. The task to update the UI when there is a change in the 
  * application state is delegated to dedicated helper controllers.
  *
  * @param actionStore             the action store
  * @param playlistTableController the table controller
  * @param currentSongController   the current song controller
  * @param plService               the playlist service
  * @param resolverService         the service to resolve file information
  * @param config                  the configuration for the application
  */
class Controller(actionStore: ActionStore,
                 playlistTableController: PlaylistTableController,
                 currentSongController: CurrentSongController,
                 plService: PlaylistService[Playlist, String],
                 resolverService: MediaFileInfoResolverService,
                 config: AudioPlayerConfig) extends MessageBusListener:

  import Controller.*

  /**
    * Stores the current UI state. This is updated in reaction to external
    * events.
    */
  private var uiState = AudioPlayerUIState.Initial

  /** Stores the latest state from the audio player. */
  private var lastPlayerStateField = AudioPlayerState.Initial

  /** Stores the last playback progress event that was received. */
  private var lastProgressEventField = PlaybackProgress(0, 0.seconds)

  override def receive: Receive =
    case playerState: AudioPlayerState =>
      handleAudioPlayerStateUpdate(playerState)

    case progress: PlaybackProgress =>
      currentSongController.playlistProgress(progress)
      lastProgressEventField = progress

  /**
    * Notifies this controller that a player-related action has been executed.
    * This causes all actions to be disabled until the updated player state
    * is received. Based on this state, the enabled state of actions is set.
    */
  def playerActionTriggered(): Unit =
    actionStore.enableGroup(PlayerActionGroup, false)

  /**
    * Returns the last [[AudioPlayerState]] received by this controller.
    *
    * @return the last known [[AudioPlayerState]]
    */
  def lastPlayerState: AudioPlayerState = lastPlayerStateField

  /**
    * Returns the last [[PlaybackProgress]] event received by this controller.
    *
    * @return the last known [[PlaybackProgress]]
    */
  def lastProgressEvent: PlaybackProgress = lastProgressEventField

  /**
    * Handles a message about a state update of the audio player. The function
    * applies the state to the current UI state and passes the result to the
    * helper controllers, so that the UI gets updated if necessary.
    *
    * @param playerState the updated state of the audio player
    */
  private def handleAudioPlayerStateUpdate(playerState: AudioPlayerState): Unit =
    updateAndPropagateState(uiState.playerStateChanged(playerState, plService))
    updateActionStates(playerState)
    handlePlaybackAutoStart(lastPlayerStateField, playerState)
    lastPlayerStateField = playerState

  /**
    * Handles the result of a media file resolve operation. The result is
    * passed to the current UI state. If necessary, this triggers another
    * update of the UI.
    *
    * @param result the result
    */
  private def resolverCallback(result: Try[Map[String, ArchiveModel.MediaFileInfo]]): Unit =
    result.failed.foreach: exception =>
      Log.error("Resolve operation for media file IDs failed.", exception)

    updateAndPropagateState(uiState.fileInfoResolved(result))

  /**
    * Stores the next state. If it is different from the previous one, the
    * helper controllers are notified. If indicated by the state, a resolve
    * operation is started.
    *
    * @param nextState the next state to be stored
    */
  private def updateAndPropagateState(nextState: AudioPlayerUIState): Unit =
    if nextState != uiState then
      uiState = nextState
      playlistTableController.handleStateUpdate(nextState)
      currentSongController.handleStateUpdate(nextState)

      if nextState.idsToResolve.nonEmpty then
        resolverService.resolveFileIDs(nextState.idsToResolve)(resolverCallback)

  /**
    * Updates the enabled states of all player-relevant actions after a change
    * in the state of the audio player.
    *
    * @param state the current state of the audio player
    */
  private def updateActionStates(state: AudioPlayerState): Unit =
    val hasCurrent = plService.currentSong(state.playlist).isDefined
    val isPlaying = hasCurrent && state.playbackActive
    enableAction(ActionStopPlayback, isPlaying)
    enableAction(ActionPreviousSong, isPlaying)
    enableAction(ActionNextSong, isPlaying)
    enableAction(ActionGotoSong, plService.size(state.playlist) > 0)
    enableAction(ActionStartPlayback, hasCurrent && !state.playbackActive)

  /**
    * Enables the specified action.
    *
    * @param name  the name of the action
    * @param state the new enabled state
    */
  private def enableAction(name: String, state: Boolean): Unit =
    actionStore.getAction(name) setEnabled state

  /**
    * Checks whether playback needs to be started when reacting on a playlist
    * change event.
    *
    * @param oldState the old state of the audio player
    * @param newState the new state of the audio player
    */
  private def handlePlaybackAutoStart(oldState: AudioPlayerState,
                                      newState: AudioPlayerState): Unit =
    if !newState.playbackActive &&
      playlistStateChanged(oldState, newState) &&
      newState.playlist.pendingSongs.nonEmpty &&
      config.autoStartMode.canStartPlayback(newState) then
      actionStore.getAction(ActionStartPlayback).execute(null)
