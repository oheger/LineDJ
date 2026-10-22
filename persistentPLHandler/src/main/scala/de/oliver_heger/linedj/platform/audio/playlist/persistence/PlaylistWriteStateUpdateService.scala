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

package de.oliver_heger.linedj.platform.audio.playlist.persistence

import de.oliver_heger.linedj.platform.audio.playlist.persistence.PlaylistWriteStateUpdateService.PlaylistWriteState
import de.oliver_heger.linedj.platform.audio2.AudioPlayerState
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import scalaz.State
import scalaz.State.*

import java.nio.file.Path
import scala.collection.immutable.Seq
import scala.concurrent.duration.{DurationLong, FiniteDuration}

object PlaylistWriteStateUpdateService:
  /**
    * A class describing the write state of a playlist. This state keeps track
    * about changes on the current playlist and which information needs to be
    * saved to disk.
    *
    * @param initialPlaylist        stores the initial playlist loaded at
    *                               initialization time
    *
    * @param playlistSeqNo          stores the sequence number of the current playlist
    * @param currentPosition        the current position that has been saved
    * @param updatedPosition        an updated position since the last save operation
    * @param pendingWriteOperations write operations to be executed, but blocked
    *                               by operations in progress
    *
    * @param writesToTrigger        write operations that can be directly executed
    * @param writesInProgress       the paths of currently executed write operations
    * @param closeRequested         flag whether a close operation was requested
    * @param canClose               flag whether a close operation is complete
    */
  final case class PlaylistWriteState(initialPlaylist: Option[Playlist],
                                      playlistSeqNo: Option[Int],
                                      currentPosition: PlaylistModel.CurrentPlaylistPosition,
                                      updatedPosition: PlaylistModel.CurrentPlaylistPosition,
                                      pendingWriteOperations: Map[Path, PlaylistSaveService.WriteOperation],
                                      writesToTrigger: Map[Path, PlaylistSaveService.WriteOperation],
                                      writesInProgress: Set[Path],
                                      closeRequested: Boolean,
                                      canClose: Boolean)

  /**
    * A data class combining properties that are required to execute a write
    * operation.
    *
    * @param pathPlaylist     the path where to store the playlist
    * @param pathPosition     the path where to store the current position
    * @param autoSaveInterval the auto-save interval for the position
    */
  final case class PlaylistWriteConfig(pathPlaylist: Path,
                                       pathPosition: Path,
                                       autoSaveInterval: FiniteDuration)

  /**
    * A data class combining information that is relevant for handling a
    * transition of a write state.
    *
    * Some state changes generate messages or require actions that need to be
    * sent or performed to properly react on the change.
    * [[PlaylistWriteStateUpdateService]] has some composed functions
    * that combine a state change with a result object of this class. The
    * caller can then execute the corresponding actions.
    *
    * @param writes        a sequence of write operations to trigger
    * @param closePossible flag whether a close operation is now possible
    */
  case class WriteStateTransitionActions(writes: Iterable[PlaylistSaveService.WriteOperation],
                                         closePossible: Boolean)
end PlaylistWriteStateUpdateService

/**
  * Interface of a service that manages the state for write operations of the
  * current playlist.
  *
  * When there are changes in the state of the current playlist, some data
  * needs to be persisted to allow the user to continue playback at the very
  * same position. This service is responsible for keeping track on the write
  * state of the playlist and to update it accordingly when change events come
  * in. The service also generates actions that need to be executed in reaction
  * to specific input events; for instance, messages to a playlist save service
  * are generated to save specific data files.
  */
trait PlaylistWriteStateUpdateService:

  import PlaylistWriteStateUpdateService.*

  /**
    * Type alias for updates of the persistent playlist state.
    */
  type StateUpdate[A] = State[PlaylistWriteState, A]

  /**
    * Updates the state for the initial playlist. The initial playlist is
    * stored, and information about the current position is updated.
    *
    * @param plService      the playlist service
    * @param playlist       the initial playlist
    * @param positionOffset the position offset
    * @param timeOffset     the time offset
    * @return the updated state
    */
  def initPlaylist(plService: PlaylistService[Playlist, String],
                   playlist: Playlist,
                   positionOffset: Long,
                   timeOffset: FiniteDuration): StateUpdate[Unit]

  /**
    * Updates the state for an update of the audio player state. This may cause
    * changes on the current playlist that in turn trigger write operations.
    *
    * @param plService   the playlist service
    * @param saveService the service to save the playlist
    * @param playerState the state of the audio player
    * @param writeConfig write configuration data
    * @return the updated state
    */
  def playerStateChange(plService: PlaylistService[Playlist, String],
                        saveService: PlaylistSaveService,
                        playerState: AudioPlayerState,
                        writeConfig: PlaylistWriteConfig): StateUpdate[Unit]

  /**
    * Updates the state for an incoming playback progress event. This may
    * cause a position write operation if the auto save interval is reached.
    *
    * @param saveService the service to save a playlist
    * @param posOfs      the position offset
    * @param timeOfs     the time offset
    * @param writeConfig write configuration data
    * @return the updated state
    */
  def playbackProgress(saveService: PlaylistSaveService,
                       posOfs: Long,
                       timeOfs: FiniteDuration,
                       writeConfig: PlaylistWriteConfig): StateUpdate[Unit]

  /**
    * Updates the state when a file has been written. This may trigger a
    * pending write operation or some other actions.
    *
    * @param path the path of the file that has been written
    * @return the updated state
    */
  def fileWritten(path: Path): StateUpdate[Unit]

  /**
    * Updates the state for an incoming close request. Forces changes on the
    * position to be written. If possible, the request can be answered
    * directly. Otherwise, all pending writes need to finish first.
    *
    * @param saveService the service to save a playlist
    * @param writeConfig write configuration data
    * @return the updated state
    */
  def closeRequest(saveService: PlaylistSaveService, writeConfig: PlaylistWriteConfig): StateUpdate[Unit]

  /**
    * Returns the pending write operations to be passed to the playlist save
    * service and updates the state accordingly. It is assumed that these
    * operations are executed directly; so the set with writes in progress is
    * updated.
    *
    * @param writeConfig write configuration data
    * @return the write messages to be sent and the updated state
    */
  def fileWriteOperations(writeConfig: PlaylistWriteConfig): StateUpdate[Iterable[PlaylistSaveService.WriteOperation]]

  /**
    * Returns a flag whether the write/update part of the playlist handler can
    * be closed now and updates the state accordingly. When triggering a close
    * request, it can take some time after pending write operations have been
    * completed. Only then, the handler can stop itself. If this function 
    * returns **true**, this should be the final state; therefore, no more 
    * state updates are done.
    *
    * @return a flag whether the playlist handler can be closed
    */
  def closePossible(): StateUpdate[Boolean]

  /**
    * Updates the write state for an incoming change event of the audio player
    * and returns an object with actions to be executed.
    *
    * @param plService   the playlist service
    * @param saveService the service to save the playlist
    * @param playerState the audio player state
    * @param writeConfig write configuration data
    * @return the updated state and transition actions
    */
  def handlePlayerStateChange(plService: PlaylistService[Playlist, String],
                              saveService: PlaylistSaveService,
                              playerState: AudioPlayerState,
                              writeConfig: PlaylistWriteConfig): StateUpdate[WriteStateTransitionActions] =
    for
      _ <- playerStateChange(plService, saveService, playerState, writeConfig)
      actions <- fetchActions(writeConfig)
    yield actions

  /**
    * Updates the state for an incoming playback progress event and returns an
    * object with actions to be executed.
    *
    * @param saveService the service to save a playlist
    * @param posOfs      the position offset
    * @param timeOfs     the time offset
    * @param writeConfig write configuration data
    * @return the updated state and transition actions
    */
  def handlePlaybackProgress(saveService: PlaylistSaveService,
                             posOfs: Long,
                             timeOfs: FiniteDuration,
                             writeConfig: PlaylistWriteConfig): StateUpdate[WriteStateTransitionActions] =
    for
      _ <- playbackProgress(saveService, posOfs, timeOfs, writeConfig)
      actions <- fetchActions(writeConfig)
    yield actions

  /**
    * Updates the state for an incoming file written notification and returns
    * an object with actions to be executed.
    *
    * @param path        the path of the file that has been written
    * @param writeConfig write configuration data
    * @return the updated state and transition actions
    */
  def handleFileWritten(path: Path, writeConfig: PlaylistWriteConfig): StateUpdate[WriteStateTransitionActions] =
    for
      _ <- fileWritten(path)
      msg <- fetchActions(writeConfig)
    yield msg

  /**
    * Updates the state for an incoming close request and returns an object
    * with actions to be executed.
    *
    * @param saveService the service to save a playlist
    * @param writeConfig write configuration data
    * @return the updated state and transition actions
    */
  def handleCloseRequest(saveService: PlaylistSaveService,
                         writeConfig: PlaylistWriteConfig): StateUpdate[WriteStateTransitionActions] =
    for
      _ <- closeRequest(saveService, writeConfig)
      msg <- fetchActions(writeConfig)
    yield msg

  /**
    * Obtains the data to construct a [[WriteStateTransitionActions]] object
    * from a state and returns it.
    *
    * @param writeConfig write configuration data
    * @return the state update for fetching transition actions
    */
  private def fetchActions(writeConfig: PlaylistWriteConfig): StateUpdate[WriteStateTransitionActions] =
    for
      writes <- fileWriteOperations(writeConfig)
      close <- closePossible()
    yield WriteStateTransitionActions(writes, close)

/**
  * The default implementation of [[PlaylistWriteStateUpdateService]].
  */
object PlaylistWriteStateUpdateServiceImpl extends PlaylistWriteStateUpdateService:
  /** Constant for a playlist position that contains only initial values. */
  private val InitialPosition = PlaylistModel.CurrentPlaylistPosition(0, 0, 0)

  /**
    * Constant for the initial write state. This state is used when the system
    * starts up.
    */
  final val InitialState: PlaylistWriteState = PlaylistWriteState(
    initialPlaylist = None,
    playlistSeqNo = None,
    currentPosition = InitialPosition,
    updatedPosition = InitialPosition,
    pendingWriteOperations = Map.empty,
    writesToTrigger = Map.empty,
    writesInProgress = Set.empty,
    closeRequested = false,
    canClose = false
  )

  import PlaylistWriteStateUpdateService.*

  override def initPlaylist(plService: PlaylistService[Playlist, String],
                            playlist: Playlist,
                            positionOffset: Long,
                            timeOffset: FiniteDuration): StateUpdate[Unit] = modify: s =>
    s.initialPlaylist match
      case Some(_) => s
      case None =>
        val index = extractIndex(plService, playlist)
        val position = PlaylistModel.CurrentPlaylistPosition(index, positionOffset, timeOffset.toSeconds)
        s.copy(
          initialPlaylist = Some(playlist),
          currentPosition = position,
          updatedPosition = position
        )

  override def playerStateChange(plService: PlaylistService[Playlist, String],
                                 saveService: PlaylistSaveService,
                                 playerState: AudioPlayerState,
                                 writeConfig: PlaylistWriteConfig): StateUpdate[Unit] =
    modify: s =>
      s.initialPlaylist match
        case Some(initPlaylist) if playerState.playlistActivated && !s.closeRequested =>
          val optWritePlaylist = createWriteForPlaylist(
            plService,
            saveService,
            s,
            initPlaylist,
            playerState,
            writeConfig
          )
          val (pos, optWritePos) = createWriteForPosition(
            plService,
            saveService,
            s,
            playerState,
            writeConfig,
            optWritePlaylist.isDefined
          )
          val writes = List(optWritePlaylist, optWritePos).flatten
          if writes.nonEmpty then
            val (mFiles, mPending) = updateWriteMaps(s, writes)
            s.copy(
              writesToTrigger = mFiles,
              pendingWriteOperations = mPending,
              playlistSeqNo = Some(playerState.playlistSeqNo),
              updatedPosition = pos
            )
          else s
        case _ => s

  override def playbackProgress(saveService: PlaylistSaveService,
                                posOfs: Long,
                                timeOfs: FiniteDuration,
                                writeConfig: PlaylistWriteConfig): StateUpdate[Unit] =
    modify: s =>
      if s.initialPlaylist.isEmpty || s.closeRequested then s
      else
        val updatedPos = s.updatedPosition.copy(position = posOfs, time = timeOfs.toSeconds)
        val writes = if timeOfs - s.currentPosition.time.seconds >= writeConfig.autoSaveInterval then
          val posSource = saveService.createPositionSource(updatedPos)
          List(PlaylistSaveService.WriteOperation(writeConfig.pathPosition, posSource))
        else
          List.empty[PlaylistSaveService.WriteOperation]
        val (mFiles, mPending) = updateWriteMaps(s, writes)
        s.copy(updatedPosition = updatedPos, pendingWriteOperations = mPending, writesToTrigger = mFiles)

  override def fileWritten(path: Path): StateUpdate[Unit] = modify: s =>
    val writesToTrigger = s.pendingWriteOperations.get(path)
      .map(w => s.writesToTrigger + (path -> w)) getOrElse s.writesToTrigger
    val inProgress = s.writesInProgress - path
    val canClose = s.closeRequested && inProgress.isEmpty && writesToTrigger.isEmpty
    s.copy(
      writesInProgress = inProgress,
      writesToTrigger = writesToTrigger,
      pendingWriteOperations = s.pendingWriteOperations - path,
      canClose = canClose
    )

  override def closeRequest(saveService: PlaylistSaveService,
                            writeConfig: PlaylistWriteConfig): StateUpdate[Unit] = modify: s =>
    if s.closeRequested then
      s
    else
      val writePos = if s.currentPosition != s.updatedPosition then
        val posSource = saveService.createPositionSource(s.updatedPosition)
        List(PlaylistSaveService.WriteOperation(writeConfig.pathPosition, posSource))
      else
        List.empty[PlaylistSaveService.WriteOperation]
      val (mFiles, mPending) = updateWriteMaps(s, writePos)
      val canClose = s.writesInProgress.isEmpty && mFiles.isEmpty
      s.copy(
        closeRequested = true,
        writesToTrigger = mFiles,
        pendingWriteOperations = mPending,
        canClose = canClose
      )

  override def fileWriteOperations(writeConfig: PlaylistWriteConfig):
  StateUpdate[Iterable[PlaylistSaveService.WriteOperation]] = State: s =>
    if s.writesToTrigger.isEmpty then
      (s, Nil)
    else
      val messages = s.writesToTrigger.values
      val nextPos = if s.writesToTrigger.contains(writeConfig.pathPosition) then
        s.updatedPosition
      else
        s.currentPosition
      val next = s.copy(
        writesToTrigger = Map.empty,
        currentPosition = nextPos,
        writesInProgress = s.writesInProgress ++ s.writesToTrigger.keys
      )
      (next, messages)

  override def closePossible(): StateUpdate[Boolean] = State: s =>
    (s, s.canClose)

  /**
    * Returns an optional [[PlaylistSaveService.WriteOperation]] object for a
    * changed playlist. Checks if there is a change on the playlist; if so, the
    * corresponding peration to persist the change is returned.
    *
    * @param plService    the playlist service
    * @param saveService  the service to save a playlist
    * @param s            the current state
    * @param initPlaylist the initial playlist
    * @param playerState  the state of the audio player
    * @param writeConfig  the config for write operations
    * @return an [[Option]] with the operation to write the playlist
    */
  private def createWriteForPlaylist(plService: PlaylistService[Playlist, String],
                                     saveService: PlaylistSaveService,
                                     s: PlaylistWriteState,
                                     initPlaylist: Playlist,
                                     playerState: AudioPlayerState,
                                     writeConfig: PlaylistWriteConfig): Option[PlaylistSaveService.WriteOperation] =
    if playlistChanged(plService, s, initPlaylist, playerState) then
      val sourcePl = saveService.createPlaylistSource(playerState.playlist)
      Some(PlaylistSaveService.WriteOperation(writeConfig.pathPlaylist, sourcePl))
    else None

  /**
    * Returns information about an updated position. The function returns an
    * updated position and an optional operation to write the position. It 
    * checks whether there is a change on the position; only then a write 
    * operation needs to be triggered.
    *
    * @param plService       the playlist service
    * @param saveService     the service to save a playlist
    * @param s               the current state
    * @param playerState     the state of the audio player
    * @param writeConfig     the config for write operations
    * @param playlistChanged a flag whether the playlist has been changed
    * @return the updated position and an optional write operation
    */
  private def createWriteForPosition(plService: PlaylistService[Playlist, String],
                                     saveService: PlaylistSaveService,
                                     s: PlaylistWriteState,
                                     playerState: AudioPlayerState,
                                     writeConfig: PlaylistWriteConfig,
                                     playlistChanged: Boolean):
  (PlaylistModel.CurrentPlaylistPosition, Option[PlaylistSaveService.WriteOperation]) =
    val newIndex = extractIndex(plService, playerState.playlist)
    val pos = if playlistChanged || newIndex != s.currentPosition.index then
      PlaylistModel.CurrentPlaylistPosition(newIndex, 0, 0) else s.currentPosition
    if pos != s.currentPosition then
      val writeOp = PlaylistSaveService.WriteOperation(writeConfig.pathPosition, saveService.createPositionSource(pos))
      (pos, Some(writeOp))
    else
      (s.updatedPosition, None)

  /**
    * Determines the updated maps for write operations based on the given map
    * with writes. This function checks for which paths write operations are in
    * progress. The operations are then assigned to the correct maps for the
    * next state.
    *
    * @param s      the current state
    * @param writes a map with write operations
    * @return a tuple with the update maps for write operations
    */
  private def updateWriteMaps(s: PlaylistWriteState, writes: Seq[PlaylistSaveService.WriteOperation]):
  (Map[Path, PlaylistSaveService.WriteOperation], Map[Path, PlaylistSaveService.WriteOperation]) =
    writes.map(w => (w.target, w)).toMap
      .foldLeft((s.writesToTrigger, s.pendingWriteOperations)): (ms, e) =>
        if s.writesInProgress contains e._1 then (ms._1, ms._2 + e)
        else (ms._1 + e, ms._2)

  /**
    * Extracts the index of a playlist. Also handles the case that the playlist
    * has no current song. In this case, the size of the playlist is returned.
    *
    * @param plService the playlist service
    * @param playlist  the playlist
    * @return the current index of this playlist
    */
  private def extractIndex(plService: PlaylistService[Playlist, String], playlist: Playlist): Int =
    plService.currentIndex(playlist) getOrElse plService.size(playlist)

  /**
    * Checks if there is a change in the current playlist of the audio player
    * compared to the playlist in the given state.
    *
    * @param plService    the playlist service
    * @param s            the current write state
    * @param initPlaylist the initial playlist of the current state
    * @param playerState  the state of the player
    * @return a flag whether the playlist has changed
    */
  private def playlistChanged(plService: PlaylistService[Playlist, String],
                              s: PlaylistWriteState, initPlaylist: Playlist,
                              playerState: AudioPlayerState): Boolean =
    s.playlistSeqNo.map(_ != playerState.playlistSeqNo)
      .getOrElse(!plService.playlistEquals(playerState.playlist, initPlaylist))
