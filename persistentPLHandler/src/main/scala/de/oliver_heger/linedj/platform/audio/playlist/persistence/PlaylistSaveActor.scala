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

import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState, PlaybackProgress}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.shared.actors.ActorFactory
import org.apache.pekko.Done
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
  * An actor implementation responsible for storing the current state of a 
  * playlist.
  *
  * This actor is notified whenever there are changes in the current state of
  * the audio player and when a _PlaybackProgressEvent_ arrives. It then
  * decides whether playlist information needs to be persisted. If so, it uses
  * [[PlaylistSaveService]] to save the corresponding files. In order to have 
  * the initial state (i.e. the persisted playlist that has been loaded from 
  * disk), an initial [[SetPlaylist]] message is expected; before this message
  * arrives, update notifications are ignored.
  *
  * The actor keeps track on ongoing write operations; a file can only be
  * written after the previous write operation completes. It also supports a
  * graceful shutdown, so that pending data can be written before the
  * application stops. This achieved with the help of a 
  * [[PlaylistWriteStateUpdateService]] instance.
  *
  * Per default, an update of the playlist position is written each time
  * playback of a new song begins. For longer songs it makes sense to write
  * intermediate updates. This can be configured by an auto save interval. A
  * save operation is then triggered after ongoing playback for this time span.
  */
object PlaylistSaveActor:
  /** The name used for the save actor. */
  final val ActorName = "PlaylistSaveActor"

  /**
    * A data class holding the parameters required to create an instance of 
    * this actor class.
    *
    * @param playlistService     the service to manage playlists
    * @param playlistSaveService the service to save playlists
    * @param updateService       the update service
    * @param writeConfig         the configuration to write playlist data
    * @param messageBus          the system message bus
    */
  final case class Params(playlistService: PlaylistService[Playlist, String],
                          playlistSaveService: PlaylistSaveService,
                          updateService: PlaylistWriteStateUpdateService,
                          writeConfig: PlaylistWriteStateUpdateService.PlaylistWriteConfig,
                          messageBus: MessageBus)

  /**
    * A trait defining a handle through which consumers interact with an actor
    * instance. Since the actor operates autonomously, this interaction is 
    * limited to stopping the instance. Since there may be some actions to be
    * performed on shutdown (saving data that has been updated, but not yet
    * persisted), this is an asynchronous operation. The handle therefore 
    * provides a [[Future]] that completes when the operation is done.
    */
  trait Handle extends AutoCloseable:
    /**
      * Returns a [[Future]] which completes after the actor has shut down. 
      * Then all data has been stored, and it is safe to exit the application.
      *
      * @return a [[Future]] to indicate a completed shutdown operation
      */
    def shutdownFuture: Future[Done]
  end Handle

  /**
    * A factory trait to create new instances of this actor class.
    */
  trait Factory:
    /**
      * Creates a new instance of this actor and returns a [[Handle]] to it.
      *
      * @param params       the parameters for the new actor
      * @param actorFactory the actor factory
      * @return the [[Handle]] to the new instance
      */
    def apply(params: Params)(using actorFactory: ActorFactory): Handle
  end Factory

  /**
    * An enumeration defining the commands handled by this actor 
    * implementation.
    */
  private enum Command:
    /**
      * A command to notify the actor that the playlist of the audio player has
      * changed. Depending on the kind of change, this may cause updates on the
      * persisted playlist.
      *
      * @param playlist       the current [[Playlist]] object
      * @param positionOffset the position (in bytes) of the first song
      * @param timeOffset     the time offset into the first song
      */
    case PlaylistChanged(playlist: Playlist,
                         positionOffset: Long,
                         timeOffset: FiniteDuration)

    /**
      * A command to notify the actor about a change in the state of the audio
      * player.
      *
      * @param state the updated player state
      */
    case AudioPlayerStateChanged(state: AudioPlayerState)

    /**
      * A command to notify the actor about a playback progress event. This
      * means that some part of the current song in the playlist was played.
      *
      * @param positionOffset the position (in bytes) that was played
      * @param timeOffset     the time that was played
      */
    case PlaybackProgress(positionOffset: Long,
                          timeOffset: FiniteDuration)

    /**
      * A command to notify the actor that a write operation of a specific file
      * has completed. The operation may fail, but this does not impact how it
      * is handled; only a log is then written.
      *
      * @param path       the path to the file that was written
      * @param optFailure an [[Option]] with an exception to indicate failure
      */
    case FileWritten(path: Path,
                     optFailure: Option[Throwable])

    /**
      * A command to trigger the shutdown process of the actor. If necessary,
      * write operations are executed to make sure that all data is persisted.
      * When everything is done, the _shutdown future_ completes.
      */
    case Stop
  end Command

  /**
    * Type alias for a state update that returns actions to be executed after
    * the update.
    */
  private type ActionsStateUpdate =
    PlaylistWriteStateUpdateServiceImpl.StateUpdate[PlaylistWriteStateUpdateService.WriteStateTransitionActions]

  /** A default [[Factory]] instance to create a new actor. */
  final val newInstance: Factory = new Factory:
    override def apply(params: Params)(using actorFactory: ActorFactory): Handle =
      val promiseShutdown = Promise[Done]()
      val behavior = setupActor(params, promiseShutdown)
      val actor = actorFactory.createTypedActor(behavior, ActorName)
      createHandle(actor, promiseShutdown)

  /**
    * Sets up the behavior for a new actor instance.
    *
    * @param params          the parameters of the new actor instance
    * @param promiseShutdown the promise to control the shutdown future
    * @return the behavior of the new instance
    */
  private def setupActor(params: Params, promiseShutdown: Promise[Done]): Behavior[Command] =
    Behaviors.setup: context =>
      given ExecutionContext = context.executionContext

      val registrationID = params.messageBus.registerListener(messageBusReceive(context.self))

      /**
        * The main command handler function of this actor.
        *
        * @param state the current write state
        * @return the updated behavior
        */
      def handleCommand(state: PlaylistWriteStateUpdateService.PlaylistWriteState): Behavior[Command] =
        Behaviors.receiveMessage:
          case Command.PlaylistChanged(playlist, positionOffset, timeOffset) =>
            val update = params.updateService.initPlaylist(params.playlistService, playlist, positionOffset, timeOffset)
            val (next, _) = update(state)
            handleCommand(next)

          case Command.AudioPlayerStateChanged(playerState) =>
            updateStateAndHandleActions(
              state,
              params.updateService.handlePlayerStateChange(
                params.playlistService,
                params.playlistSaveService,
                playerState,
                params.writeConfig
              )
            )

          case Command.PlaybackProgress(positionOffset, timeOffset) =>
            updateStateAndHandleActions(
              state,
              params.updateService.handlePlaybackProgress(
                params.playlistSaveService,
                positionOffset,
                timeOffset,
                params.writeConfig
              )
            )

          case Command.FileWritten(path, optFailure) =>
            optFailure.foreach: exception =>
              context.log.error("Could not store {}.", path, exception)
            updateStateAndHandleActions(
              state,
              params.updateService.handleFileWritten(path, params.writeConfig)
            )

          case Command.Stop =>
            context.log.info("Request to close {}.", ActorName)
            updateStateAndHandleActions(
              state,
              params.updateService.handleCloseRequest(params.playlistSaveService, params.writeConfig)
            )

      /**
        * Performs an update operation of the current state and processes state
        * transition actions that are generated by the update.
        *
        * @param state  the current state
        * @param update the update to be executed
        */
      def updateStateAndHandleActions(state: PlaylistWriteStateUpdateService.PlaylistWriteState,
                                      update: ActionsStateUpdate): Behavior[Command] =
        val (next, actions) = update(state)
        actions.writes.foreach(triggerWriteOperation)
        if actions.closePossible then
          promiseShutdown.success(Done)
          context.log.info("Stopping {}.", ActorName)
          params.messageBus.removeListener(registrationID)
          Behaviors.stopped
        else
          handleCommand(next)

      /**
        * Calls the save service to start a write operation. When this 
        * asynchronous operation completes, this actor gets notified.
        *
        * @param writeOp the affected write operation
        */
      def triggerWriteOperation(writeOp: PlaylistSaveService.WriteOperation): Unit =
        context.log.info("Writing file {}.", writeOp.target)
        params.playlistSaveService.store(writeOp).onComplete: triedResult =>
          val optFailure = triedResult match
            case Success(_) => None
            case Failure(exception) => Some(exception)
          context.self ! Command.FileWritten(writeOp.target, optFailure)

      handleCommand(PlaylistWriteStateUpdateServiceImpl.InitialState)

  /**
    * Returns a receiver function for the system message bus. The function
    * converts relevant messages to commands and forwards them to the actor.
    *
    * @param actor the actor instance
    * @return the receiver function for the message bus
    */
  private def messageBusReceive(actor: ActorRef[Command]): Receive =
    case pl: AudioPlayerCommands.SetPlaylist =>
      actor ! Command.PlaylistChanged(pl.playlist, pl.positionOffset, pl.timeOffset)

    case PlaybackProgress(posOffset, timeOffset) =>
      actor ! Command.PlaybackProgress(posOffset, timeOffset)

    case state: AudioPlayerState =>
      actor ! Command.AudioPlayerStateChanged(state)

  /**
    * Creates a handle for a new actor instance.
    *
    * @param actor           the underlying actor
    * @param promiseShutdown the promise for the shutdown future
    * @return the handle for this actor
    */
  private def createHandle(actor: ActorRef[Command], promiseShutdown: Promise[Done]): Handle =
    new Handle:
      override def shutdownFuture: Future[Done] = promiseShutdown.future

      override def close(): Unit = actor ! Command.Stop
