/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.playlist.persistence

import de.oliver_heger.linedj.io.stream.ListSeparatorStage
import de.oliver_heger.linedj.platform.audio.AudioPlayerState
import de.oliver_heger.linedj.platform.audio.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.playlist.persistence.PersistentPlaylistModel.*
import de.oliver_heger.linedj.playlist.persistence.PlaylistFileWriterActor.WriteFile
import de.oliver_heger.linedj.shared.archive.media.MediaFileID
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import scalaz.State
import scalaz.State.*
import spray.json.*

import java.nio.file.Path
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

/**
  * A class describing the write state of a playlist. This state keeps track
  * about changes on the current playlist and which information needs to be
  * saved to disk.
  *
  * @param initialPlaylist        stores the initial playlist loaded at
  *                               initialization time
  * @param playlistSeqNo          stores the sequence number of the current playlist
  * @param currentPosition        the current position that has been saved
  * @param updatedPosition        an updated position since the last save operation
  * @param pendingWriteOperations write operations to be executed, but blocked
  *                               by operations in progress
  * @param writesToTrigger        write operations that can be directly executed
  * @param writesInProgress       the paths of currently executed write operations
  * @param closeRequest           stores an actor that initiated a close operation
  * @param canClose               flag whether a close operation is complete
  */
case class PlaylistWriteState(initialPlaylist: Option[Playlist],
                              playlistSeqNo: Option[Int],
                              currentPosition: CurrentPlaylistPosition,
                              updatedPosition: CurrentPlaylistPosition,
                              pendingWriteOperations: Map[Path, WriteFile],
                              writesToTrigger: Map[Path, WriteFile],
                              writesInProgress: Set[Path],
                              closeRequest: Option[ActorRef],
                              canClose: Boolean)

/**
  * A data class combining properties that are required to execute a write
  * operation.
  *
  * @param pathPlaylist     the path where to store the playlist
  * @param pathPosition     the path where to store the current position
  * @param autoSaveInterval the auto-save interval for the position
  */
case class PlaylistWriteConfig(pathPlaylist: Path, pathPosition: Path,
                               autoSaveInterval: FiniteDuration)

/**
  * A data class combining information that is relevant for handling a
  * transition of a write state.
  *
  * Some state changes generate messages that need to be sent to collaborating
  * actors. ''PlaylistWriteStateUpdateService'' has some composed functions
  * that combine a state change with a result object of this class. The
  * caller can then send corresponding notifications.
  *
  * @param writes   a sequence of ''WriteFile'' operations to trigger
  * @param closeAck an optional actor to send a close ACK message
  */
case class WriteStateTransitionMessages(writes: Iterable[WriteFile], closeAck: Option[ActorRef])

/**
  * Interface of a service that manages the state for write operations of the
  * current playlist.
  *
  * When there are changes in the state of the current playlist, some data
  * needs to be persisted to allow the user to continue playback at the very
  * same position. This service is responsible for keeping track on the write
  * state of the playlist and to update it accordingly when change events come
  * in. The service also generates actions that need to be executed in reaction
  * to specific input events; for instance, messages to a file writer actor are
  * generated to save specific data files.
  */
trait PlaylistWriteStateUpdateService:
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
  def initPlaylist(plService: PlaylistService[Playlist, MediaFileID], playlist: Playlist,
                   positionOffset: Long, timeOffset: Long): StateUpdate[Unit]

  /**
    * Updates the state for an update of the audio player state. This may cause
    * changes on the current playlist that in turn trigger write operations.
    *
    * @param plService   the playlist service
    * @param playerState the state of the audio player
    * @param writeConfig write configuration data
    * @return the updated state
    */
  def playerStateChange(plService: PlaylistService[Playlist, MediaFileID],
                        playerState: AudioPlayerState,
                        writeConfig: PlaylistWriteConfig): StateUpdate[Unit]

  /**
    * Updates the state for an incoming playback progress event. This may
    * cause a position write operation if the auto save interval is reached.
    *
    * @param posOfs      the position offset
    * @param timeOfs     the time offset
    * @param writeConfig write configuration data
    * @return the updated state
    */
  def playbackProgress(posOfs: Long, timeOfs: Long, writeConfig: PlaylistWriteConfig):
  StateUpdate[Unit]

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
    * @param client      the requesting actor
    * @param writeConfig write configuration data
    * @return the updated state
    */
  def closeRequest(client: ActorRef, writeConfig: PlaylistWriteConfig): StateUpdate[Unit]

  /**
    * Returns the write messages to be sent to the file writer actor and
    * updates the state accordingly. It is assumed that these messages are
    * sent out directly; so the set with writes in progress is updated.
    *
    * @param writeConfig write configuration data
    * @return the write messages to be sent and the updated state
    */
  def fileWriteMessages(writeConfig: PlaylistWriteConfig): StateUpdate[Iterable[WriteFile]]

  /**
    * Returns an option with the actor to send a close ACK message to and
    * updates the state accordingly. If a close ACK can be sent, this should be
    * the final state; therefore, no more state updates are done.
    *
    * @return an option with the actor to send a close ACK and the updated
    *         state
    */
  def closeActor(): StateUpdate[Option[ActorRef]]

  /**
    * Updates the write state for an incoming change event of the audio player
    * and returns an object with messages to be sent.
    *
    * @param plService   the playlist service
    * @param playerState the audio player state
    * @param writeConfig write configuration data
    * @return the updated state and transition messages
    */
  def handlePlayerStateChange(plService: PlaylistService[Playlist, MediaFileID],
                              playerState: AudioPlayerState,
                              writeConfig: PlaylistWriteConfig):
  StateUpdate[WriteStateTransitionMessages] = for
    _ <- playerStateChange(plService, playerState, writeConfig)
    msg <- fetchMessages(writeConfig)
  yield msg

  /**
    * Updates the state for an incoming playback progress event and returns an
    * object with messages to be sent.
    *
    * @param posOfs      the position offset
    * @param timeOfs     the time offset
    * @param writeConfig write configuration data
    * @return the updated state and transition messages
    */
  def handlePlaybackProgress(posOfs: Long, timeOfs: Long, writeConfig: PlaylistWriteConfig):
  StateUpdate[WriteStateTransitionMessages] = for
    _ <- playbackProgress(posOfs, timeOfs, writeConfig)
    msg <- fetchMessages(writeConfig)
  yield msg

  /**
    * Updates the state for an incoming file written notification and returns
    * an object with messages to be sent.
    *
    * @param path        the path of the file that has been written
    * @param writeConfig write configuration data
    * @return the updated state and transition messages
    */
  def handleFileWritten(path: Path, writeConfig: PlaylistWriteConfig):
  StateUpdate[WriteStateTransitionMessages] = for
    _ <- fileWritten(path)
    msg <- fetchMessages(writeConfig)
  yield msg

  /**
    * Updates the state for an incoming close request and returns an object
    * with messages to be sent.
    *
    * @param client      the requesting actor
    * @param writeConfig write configuration data
    * @return the updated state and transition messages
    */
  def handleCloseRequest(client: ActorRef, writeConfig: PlaylistWriteConfig):
  StateUpdate[WriteStateTransitionMessages] = for
    _ <- closeRequest(client, writeConfig)
    msg <- fetchMessages(writeConfig)
  yield msg

  /**
    * Obtains the data to construct a ''WriteStateTransitionMessages'' object
    * from a state and returns it.
    *
    * @param writeConfig write configuration data
    * @return the state update for fetching transition messages
    */
  private def fetchMessages(writeConfig: PlaylistWriteConfig):
  StateUpdate[WriteStateTransitionMessages] = for
    writes <- fileWriteMessages(writeConfig)
    ack <- closeActor()
  yield WriteStateTransitionMessages(writes, ack)

/**
  * The default implementation of ''PlaylistWriteStateUpdateService''.
  */
object PlaylistWriteStateUpdateServiceImpl extends PlaylistWriteStateUpdateService {
  /**
    * Constant for a playlist position that contains only initial values.
    */
  private val InitialPosition = CurrentPlaylistPosition(0, 0, 0)

  /**
    * Constant for the initial write state. This state is used when the system
    * starts up.
    */
  val InitialState: PlaylistWriteState = PlaylistWriteState(initialPlaylist = None,
    playlistSeqNo = None,
    currentPosition = InitialPosition,
    updatedPosition = InitialPosition,
    pendingWriteOperations = Map.empty,
    writesToTrigger = Map.empty,
    writesInProgress = Set.empty,
    closeRequest = None,
    canClose = false)

  override def initPlaylist(plService: PlaylistService[Playlist, MediaFileID],
                            playlist: Playlist, positionOffset: Long, timeOffset: Long):
  StateUpdate[Unit] = modify { s =>
    s.initialPlaylist match
      case Some(_) => s
      case None =>
        val index = extractIndex(plService, playlist)
        val position = CurrentPlaylistPosition(index, positionOffset, timeOffset)
        s.copy(initialPlaylist = Some(playlist), currentPosition = position,
          updatedPosition = position)
  }

  override def playerStateChange(plService: PlaylistService[Playlist, MediaFileID],
                                 playerState: AudioPlayerState,
                                 writeConfig: PlaylistWriteConfig): StateUpdate[Unit] =
    modify { s =>
      s.initialPlaylist match
        case Some(initPlaylist) if playerState.playlistActivated && s.closeRequest.isEmpty =>
          val optWritePlaylist = createWriteForPlaylist(plService, s, initPlaylist, playerState,
            writeConfig)
          val (pos, optWritePos) = createWriteForPosition(plService, s, playerState, writeConfig,
            optWritePlaylist.isDefined)
          val writes = List(optWritePlaylist, optWritePos).flatten
          if writes.nonEmpty then
            val (mFiles, mPending) = updateWriteMaps(s, writes)
            s.copy(writesToTrigger = mFiles, pendingWriteOperations = mPending,
              playlistSeqNo = Some(playerState.playlistSeqNo), updatedPosition = pos)
          else s
        case _ => s
    }

  override def playbackProgress(posOfs: Long, timeOfs: Long, writeConfig: PlaylistWriteConfig):
  StateUpdate[Unit] = modify { s =>
    if s.initialPlaylist.isEmpty || s.closeRequest.isDefined then s
    else
      val updatedPos = s.updatedPosition.copy(position = posOfs, time = timeOfs)
      val writes = if timeOfs - s.currentPosition.time >=
        writeConfig.autoSaveInterval.toSeconds then
        List(WriteFile(createPositionSource(updatedPos), writeConfig.pathPosition))
      else List.empty[WriteFile]
      val (mFiles, mPending) = updateWriteMaps(s, writes)
      s.copy(updatedPosition = updatedPos, pendingWriteOperations = mPending,
        writesToTrigger = mFiles)
  }

  override def fileWritten(path: Path): StateUpdate[Unit] = modify { s =>
    val writesToTrigger = s.pendingWriteOperations.get(path)
      .map(w => s.writesToTrigger + (path -> w)) getOrElse s.writesToTrigger
    val inProgress = s.writesInProgress - path
    val canClose = s.closeRequest.isDefined && inProgress.isEmpty && writesToTrigger.isEmpty
    s.copy(writesInProgress = inProgress, writesToTrigger = writesToTrigger,
      pendingWriteOperations = s.pendingWriteOperations - path,
      canClose = canClose)
  }

  override def closeRequest(client: ActorRef, writeConfig: PlaylistWriteConfig):
  StateUpdate[Unit] = modify { s =>
    s.closeRequest match
      case Some(_) => s
      case None =>
        val writePos = if s.currentPosition != s.updatedPosition then
          List(WriteFile(createPositionSource(s.updatedPosition), writeConfig.pathPosition))
        else List.empty[WriteFile]
        val (mFiles, mPending) = updateWriteMaps(s, writePos)
        val canClose = s.writesInProgress.isEmpty && mFiles.isEmpty
        s.copy(closeRequest = Some(client), writesToTrigger = mFiles,
          pendingWriteOperations = mPending, canClose = canClose)
  }

  override def fileWriteMessages(writeConfig: PlaylistWriteConfig):
  StateUpdate[Iterable[WriteFile]] = State { s =>
    if s.writesToTrigger.isEmpty then (s, Nil)
    else
      val messages = s.writesToTrigger.values
      val nextPos = if s.writesToTrigger contains writeConfig.pathPosition then
        s.updatedPosition else s.currentPosition
      val next = s.copy(writesToTrigger = Map.empty, currentPosition = nextPos,
        writesInProgress = s.writesInProgress ++ s.writesToTrigger.keys)
      (next, messages)
  }

  override def closeActor(): StateUpdate[Option[ActorRef]] = State { s =>
    (s, s.closeRequest)
  }

  /**
    * Returns an optional ''WriteFile'' message for a changed playlist. Checks
    * if there is a change on the playlist; if so, the corresponding message to
    * trigger the write is returned.
    *
    * @param plService    the playlist service
    * @param s            the current state
    * @param initPlaylist the initial playlist
    * @param playerState  the state of the audio player
    * @param writeConfig  the config for write operations
    * @return an ''Option'' with the message to write the playlist
    */
  private def createWriteForPlaylist(plService: PlaylistService[Playlist, MediaFileID],
                                     s: PlaylistWriteState,
                                     initPlaylist: Playlist,
                                     playerState: AudioPlayerState,
                                     writeConfig: PlaylistWriteConfig): Option[WriteFile] =
    if playlistChanged(plService, s, initPlaylist, playerState) then
      val sourcePl = createPlaylistSource(plService, playerState.playlist)
      Some(WriteFile(sourcePl, writeConfig.pathPlaylist))
    else None

  /**
    * Returns information about an updated position. The function returns an
    * updated position and an optional message to write the position. It checks
    * whether there is a change on the position; only then a write operation
    * needs to be triggered.
    *
    * @param plService       the playlist service
    * @param s               the current state
    * @param playerState     the state of the audio player
    * @param writeConfig     the config for write operations
    * @param playlistChanged a flag whether the playlist has been changed
    * @return the updated position and an optional write message
    */
  private def createWriteForPosition(plService: PlaylistService[Playlist, MediaFileID],
                                     s: PlaylistWriteState,
                                     playerState: AudioPlayerState,
                                     writeConfig: PlaylistWriteConfig,
                                     playlistChanged: Boolean):
  (CurrentPlaylistPosition, Option[WriteFile]) =
    val newIndex = extractIndex(plService, playerState.playlist)
    val pos = if playlistChanged || newIndex != s.currentPosition.index then
      CurrentPlaylistPosition(newIndex, 0, 0) else s.currentPosition
    if pos != s.currentPosition then
      (pos, Some(WriteFile(createPositionSource(pos), writeConfig.pathPosition)))
    else (s.updatedPosition, None)

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
  private def updateWriteMaps(s: PlaylistWriteState, writes: Seq[WriteFile]):
  (Map[Path, WriteFile], Map[Path, WriteFile]) =
    writes.map(w => (w.target, w)).toMap
      .foldLeft((s.writesToTrigger, s.pendingWriteOperations)) { (ms, e) =>
        if s.writesInProgress contains e._1 then (ms._1, ms._2 + e)
        else (ms._1 + e, ms._2)
      }

  /**
    * Extracts the index of a playlist. Also handles the case that the playlist
    * has no current song. In this case, the size of the playlist is returned.
    *
    * @param plService the playlist service
    * @param playlist  the playlist
    * @return the current index of this playlist
    */
  private def extractIndex(plService: PlaylistService[Playlist, MediaFileID], playlist: Playlist)
  : Int = plService.currentIndex(playlist) getOrElse plService.size(playlist)

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
  private def playlistChanged(plService: PlaylistService[Playlist, MediaFileID],
                              s: PlaylistWriteState, initPlaylist: Playlist,
                              playerState: AudioPlayerState): Boolean =
    s.playlistSeqNo.map(_ != playerState.playlistSeqNo)
      .getOrElse(!plService.playlistEquals(playerState.playlist, initPlaylist))

  /**
    * Creates the source for persisting a playlist.
    *
    * @param plService the playlist service
    * @param playlist  the playlist
    * @return the source for writing out this playlist
    */
  private def createPlaylistSource(plService: PlaylistService[Playlist, MediaFileID],
                                   playlist: Playlist): Source[ByteString, Any] =
    val sourcePl = Source(plService.toSongList(playlist))
    val sepStage =
      new ListSeparatorStage[MediaFileID]("[\n", ",\n", "\n]\n")(convertItem)
    sourcePl.via(sepStage)

  /**
    * Creates the source for persisting position information.
    *
    * @param position the position to be persisted
    * @return the source for writing out this position
    */
  private def createPositionSource(position: CurrentPlaylistPosition): Source[ByteString, Any] =
    Source.single(convertPosition(position))

  /**
    * Generates a string for the specified playlist item.
    *
    * @param item the item in the playlist
    * @param idx  the index of this item
    * @return a string representation of this item
    */
  private def convertItem(item: MediaFileID, idx: Int): String =
    convertToPersistentModel(PlaylistItemData(idx, item)).toJson.prettyPrint

  /**
    * Generates a string for the specified current playlist position.
    *
    * @param position the position
    * @return the JSON representation for this position
    */
  private def convertPosition(position: CurrentPlaylistPosition): ByteString =
    ByteString(position.toJson.prettyPrint)
}
