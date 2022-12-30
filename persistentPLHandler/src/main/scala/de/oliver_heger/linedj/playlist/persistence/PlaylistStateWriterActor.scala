/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.platform.audio.playlist.service.PlaylistService
import de.oliver_heger.linedj.platform.audio.{AudioPlayerState, SetPlaylist}
import de.oliver_heger.linedj.player.engine.PlaybackProgressEvent
import de.oliver_heger.linedj.playlist.persistence.PlaylistFileWriterActor.{FileWritten, WriteFile}
import de.oliver_heger.linedj.utils.ChildActorFactory

object PlaylistStateWriterActor {
  /**
    * Creates a ''Props'' object for the creation of a new actor instance.
    *
    * @param writeConfig the object with configuration data
    * @return ''Props'' for a new actor instance
    */
  def apply(writeConfig: PlaylistWriteConfig): Props =
    Props(classOf[PlaylistStateWriterActorImpl], writeConfig)

  private class PlaylistStateWriterActorImpl(writeConfig: PlaylistWriteConfig)
    extends PlaylistStateWriterActor(writeConfig) with ChildActorFactory

}

/**
  * An actor responsible for storing the current state of a playlist.
  *
  * This actor is notified whenever there are changes in the current state of
  * the audio player and when a ''PlaybackProgressEvent'' arrives. It then
  * decides whether playlist information needs to be persisted. If so, it calls
  * a child writer actor with the data to be persisted. In order to have the
  * initial state (i.e. the persisted playlist that has been loaded from disk),
  * an initial ''SetPlaylist'' message is expected; before this message
  * arrives, update notifications are ignored.
  *
  * The actor keeps track on ongoing write operations; a file can only be
  * written after the previous write operation completes. It also supports a
  * graceful shutdown, so that pending data can be written before the
  * application stops.
  *
  * Per default, an update of the playlist position is written each time
  * playback of a new song begins. For longer songs it makes sense to write
  * intermediate updates. This can be configured by an auto save interval. A
  * save operation is then triggered after ongoing playback for this time span.
  *
  * @param updateService the service for updating the playlist write state
  * @param writeConfig   the configuration for writing the playlist
  */
class PlaylistStateWriterActor(private[persistence]
                               val updateService: PlaylistWriteStateUpdateService,
                               writeConfig: PlaylistWriteConfig) extends Actor
  with ActorLogging {
  me: ChildActorFactory =>

  def this(writeConfig: PlaylistWriteConfig) =
    this(PlaylistWriteStateUpdateServiceImpl, writeConfig)

  /** The playlist service. */
  private val plService = PlaylistService

  /** The child actor for file write operations. */
  private var fileWriterActor: ActorRef = _

  /** The state of the written playlist. */
  private var state = PlaylistWriteStateUpdateServiceImpl.InitialState

  override def preStart(): Unit = {
    super.preStart()
    fileWriterActor = createChildActor(Props[PlaylistFileWriterActor]())
  }

  override def receive: Receive = {
    case SetPlaylist(playlist, _, positionOffset, timeOffset) =>
      updateState(updateService.initPlaylist(plService, playlist, positionOffset, timeOffset))

    case playerState: AudioPlayerState =>
      updateStateAndHandleMessages(updateService.handlePlayerStateChange(plService,
        playerState, writeConfig))

    case PlaybackProgressEvent(ofs, time, _, _) =>
      updateStateAndHandleMessages(updateService.handlePlaybackProgress(ofs, time.toSeconds, writeConfig))

    case FileWritten(path, _) =>
      updateStateAndHandleMessages(updateService.handleFileWritten(path, writeConfig))

    case CloseRequest =>
      updateStateAndHandleMessages(updateService.handleCloseRequest(sender(), writeConfig))
  }

  /**
    * Performs an update operation on the current state.
    *
    * @param update the update to be executed
    * @tparam A the type of the result of the update
    * @return the result of the update operation
    */
  private def updateState[A](update: PlaylistWriteStateUpdateServiceImpl.StateUpdate[A]): A = {
    val (next, res) = update(state)
    state = next
    res
  }

  /**
    * Performs an update operation of the current state and processes state
    * transition messages that are generated by the update.
    *
    * @param update the update to be executed
    */
  private def updateStateAndHandleMessages(update: PlaylistWriteStateUpdateServiceImpl
  .StateUpdate[WriteStateTransitionMessages]): Unit = {
    val messages = updateState(update)
    messages.writes foreach callWriteActor
    messages.closeAck foreach (_ ! CloseAck(self))
  }

  /**
    * Actually invokes the write actor with the specified data and records the
    * ongoing write operation.
    *
    * @param writeMsg the write message
    */
  private def callWriteActor(writeMsg: WriteFile): Unit = {
    fileWriterActor ! writeMsg
    log.info("Saving playlist information to {}.", writeMsg.target)
  }
}
