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

package de.oliver_heger.linedj.platform.audio.impl

import java.time.LocalDateTime

import de.oliver_heger.linedj.platform.audio._
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.mediaifc.ext.NoGroupingMediaIfcExtension
import de.oliver_heger.linedj.player.engine.{AudioSource, AudioSourceFinishedEvent, AudioSourcePlaylistInfo, PlaybackProgressEvent}
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer
import de.oliver_heger.linedj.shared.archive.media.MediaFileID

/**
  * Controller class that updates an audio player object based on messages
  * received on the central message bus.
  *
  * This class exposes the functionality of an ''AudioPlayer'' object via the
  * system message bus. It handles specific command messages and updates the
  * player accordingly. In addition, a state for the audio player is
  * maintained. Components can register themselves as consumers for state
  * change events and are then notified whenever the player's state changes.
  *
  * @param player          the player object to be managed
  * @param messageBus      the UI message bus
  * @param playlistService the service for dealing with playlist objects
  */
private class AudioPlayerController(val player: AudioPlayer,
                                    val messageBus: MessageBus,
                                    playlistService: PlaylistService[Playlist, MediaFileID]
                                    = PlaylistService)
  extends NoGroupingMediaIfcExtension[AudioPlayerStateChangedEvent] {
  /** Stores the last state change event. */
  private var lastEvent = AudioPlayerStateChangedEvent(AudioPlayerState.Initial)

  /**
    * The time when the last reset of the player engine took place. This is
    * recorded to detect events from the player engine that happened before
    * this reset. Such events have to be ignored.
    */
  private var lastResetTime = LocalDateTime.now()

  /**
    * Returns the message handling function for this object.
    *
    * @return a message handling function for specific events
    */
  override protected def receiveSpecific = {
    case reg: AudioPlayerStateChangeRegistration =>
      addConsumer(reg)
      reg.callback(lastEvent)

    case cmd: SetPlaylist =>
      handleSetPlaylist(cmd)

    case cmd: AppendPlaylist =>
      handleAppendPlaylist(cmd)

    case StartAudioPlayback(delay) =>
      if (!currentState.playbackActive) {
        ensurePlaylistActivated()
        player startPlayback delay
        updateState(_.copy(playbackActive = true, playlistActivated = true))
      }

    case StopAudioPlayback(delay) =>
      if (currentState.playbackActive) {
        player stopPlayback delay
        updateState(_.copy(playbackActive = false))
      }

    case SkipCurrentSource =>
      if (currentState.playbackActive) {
        player.skipCurrentSource()
      }

    case AudioSourceFinishedEvent(source, time) =>
      handleSourceFinishedEvent(source, time)
  }

  /**
    * Handles a command that sets a new playlist. The current state is updated
    * to match the new playlist.
    *
    * @param cmd the set playlist command
    */
  private def handleSetPlaylist(cmd: SetPlaylist): Unit = {
    if (hasActivePlaylist) {
      resetPlayer()
    }
    lastResetTime = LocalDateTime.now()
    cmd.playlist.pendingSongs match {
      case h :: t =>
        player addToPlaylist toPlaylistInfo(h, cmd.positionOffset, cmd.timeOffset)
        appendSongsToPlaylist(t, cmd.closePlaylist)
        messageBus publish createProgressEvent(h, cmd.positionOffset, cmd.timeOffset)
      case _ =>
      // no action for empty list of pending songs
    }
    updateState { s =>
      val seqNo = if (playlistService.playlistEquals(s.playlist, cmd.playlist)) s.playlistSeqNo
      else playlistService.incrementPlaylistSeqNo(s.playlistSeqNo)
      s.copy(playlist = cmd.playlist, playlistClosed = cmd.closePlaylist, playlistSeqNo = seqNo,
        playlistActivated = cmd.playlist.pendingSongs.nonEmpty)
    }
  }

  /**
    * Handles a command to add songs to the current playlist. It is possible to
    * add songs without activating them, i.e. passing them to the player
    * engine. This can be useful if the playlist is going to be manipulated
    * before the songs are actually played.
    *
    * @param cmd the append playlist command
    */
  private def handleAppendPlaylist(cmd: AppendPlaylist): Unit = {
    if (!currentState.playlistClosed) {
      val needActivate = cmd.closePlaylist || cmd.activate || currentState.playlistActivated
      if (needActivate) {
        ensurePlaylistActivated()
        appendSongsToPlaylist(cmd.songs, cmd.closePlaylist)
      }
      updateState(s =>
        s.copy(playlist = s.playlist.copy(pendingSongs = s.playlist.pendingSongs ++ cmd.songs),
          playlistSeqNo = playlistService.incrementPlaylistSeqNo(s.playlistSeqNo),
          playlistActivated = needActivate))
    }
  }

  /**
    * Handles an event about a finished audio source. If the source affected is
    * the current song in the playlist, the controller's state is updated
    * accordingly.
    *
    * @param source the source of the event
    * @param time   the time of the event
    */
  private def handleSourceFinishedEvent(source: AudioSource, time: LocalDateTime): Unit = {
    if (playlistService.currentSong(currentState.playlist)
      .exists(_.uri == source.uri) && isCurrentPlayerEvent(time)) {
      updateState { s =>
        val nextPlaylist = playlistService.moveForwards(s.playlist).get
        val nextState = s.copy(playlist = nextPlaylist)
        if (playlistService.currentSong(nextPlaylist).isEmpty &&
          s.playlistClosed && s.playbackActive)
          nextState.copy(playbackActive = false)
        else nextState
      }
    }
  }

  /**
    * Resets the player engine. Makes sure that the playback state is restored
    * after the reset.
    */
  private def resetPlayer(): Unit = {
    val playbackActive = lastEvent.state.playbackActive
    player.reset()
    if (playbackActive) {
      player.startPlayback()
    }
  }

  /**
    * Adds the specified songs to the audio player's playlist. Optionally, the
    * playlist can then be closed.
    *
    * @param songs         the songs to be appended
    * @param closePlaylist flag whether the playlist should be closed
    */
  private def appendSongsToPlaylist(songs: List[MediaFileID],
                                    closePlaylist: Boolean): Unit = {
    songs map (toPlaylistInfo(_, 0, 0)) foreach (s => player addToPlaylist s)
    if (closePlaylist) {
      player.closePlaylist()
    }
  }

  /**
    * Makes sure that the songs of the current playlist have been activated.
    * If necessary, the songs are passed to the player engine.
    */
  private def ensurePlaylistActivated(): Unit = {
    if (!currentState.playlistActivated) {
      appendSongsToPlaylist(currentState.playlist.pendingSongs, closePlaylist = false)
    }
  }

  /**
    * Creates a progress event with the specified parameters. Such an event is
    * published after a new playlist has been set. Thus, interested parties get
    * information about the offset parameters of the first song.
    *
    * @param fileID  the ID of the current song file
    * @param posOfs  the position offset
    * @param timeOfs the time offset
    * @return the new event
    */
  private def createProgressEvent(fileID: MediaFileID, posOfs: Long, timeOfs: Long)
  : PlaybackProgressEvent = {
    val source = AudioSource(fileID.uri, AudioSource.UnknownLength, posOfs, timeOfs)
    val event = PlaybackProgressEvent(posOfs, timeOfs, source)
    event
  }

  /**
    * Creates an ''AudioSourcePlaylistInfo'' object from the provided
    * parameters.
    *
    * @param id      the ID
    * @param posOfs  the position offset
    * @param timeOfs the time offset
    * @return the ''AudioSourcePlaylistInfo''
    */
  private def toPlaylistInfo(id: MediaFileID, posOfs: Long, timeOfs: Long):
  AudioSourcePlaylistInfo =
    AudioSourcePlaylistInfo(id, skip = posOfs, skipTime = timeOfs)

  /**
    * Updates the current state and sends a corresponding state change event to
    * all registered consumers.
    *
    * @param trans a function that updates the current state
    */
  private def updateState(trans: AudioPlayerState => AudioPlayerState): Unit = {
    val nextState = trans(currentState)
    lastEvent = AudioPlayerStateChangedEvent(nextState)
    invokeConsumers(lastEvent)
  }

  /**
    * Returns the current ''AudioPlayerState''.
    *
    * @return the current ''AudioPlayerState''
    */
  private def currentState: AudioPlayerState = lastEvent.state

  /**
    * Checks whether a current playlist exists and has been activated.
    *
    * @return a flag if a current playlist exists
    */
  private def hasActivePlaylist: Boolean =
    (currentState.playlist.pendingSongs.nonEmpty || currentState.playlist.playedSongs.nonEmpty) &&
      currentState.playlistActivated

  /**
    * Checks whether the given event time indicates a current audio player
    * event. Events triggered before the last reset of the player engine must
    * be ignored. This function checks the event time against the time of the
    * last reset.
    *
    * @param time the time of the event
    * @return a flag whether this is a current event (which needs to be
    *         handled)
    */
  private def isCurrentPlayerEvent(time: LocalDateTime): Boolean =
    time.isAfter(lastResetTime) || time == lastResetTime
}
