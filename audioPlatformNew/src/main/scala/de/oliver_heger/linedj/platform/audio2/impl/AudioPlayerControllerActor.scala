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

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.platform.archiveclient.ArchiveService
import de.oliver_heger.linedj.platform.audio2.playlist.{Playlist, PlaylistService}
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState, PlaybackProgress}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.player.engine.AsyncAudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.{LineWriterStage, PausePlaybackStage}
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.KillSwitch

import scala.concurrent.Promise
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * An object providing an actor implementation to manage audio playback for 
  * the audio platform.
  *
  * An instance is created when the audio platform starts. It manages state 
  * related to audio playback and also a current [[AudioPlayerActor]] instance.
  * Having this logic in an actor ensures thread-safety in a natural way.
  */
object AudioPlayerControllerActor:
  /** The name of the managed audio player actor instance. */
  private val AudioPlayerActorName = "audioPlayerActor"

  /**
    * A command that tells the audio player controller actor to stop itself.
    * This command is sent to the actor when the audio platform shuts down.
    */
  case object Stop

  /**
    * The type of the (external) commands supported by this actor 
    * implementation.
    */
  type AudioPlayerControllerCommand = AudioPlayerCommands | Stop.type

  /**
    * Type alias for all commands (internal and external ones) supported by 
    * this actor implementation.
    */
  private type AudioPlayerInternalControllerCommand = AudioPlayerControllerCommand |
    LineWriterStage.PlayedAudioChunk |
    AudioPlayerActor.PlaylistEvent

  /**
    * A data class representing the internal state that is managed by an actor
    * instance.
    *
    * @param playerState           the state of audio player
    * @param audioPlayerActor      the actor managing the audio player engine
    * @param playerActorCount      a counter to generate unique actor names
    * @param currentKillSwitch     a kill switch to cancel the current file
    * @param currentBytesProcessed the progress in bytes in the current file
    * @param currentPlaybackTime   the progress in time in the current file
    * @param lastProgressTime      time of the last progress event
    */
  private case class AudioPlayerControllerState(playerState: AudioPlayerState,
                                                audioPlayerActor: ActorRef[AudioPlayerActor.AudioPlayerCommand],
                                                playerActorCount: Int,
                                                currentKillSwitch: Option[KillSwitch],
                                                currentBytesProcessed: Long,
                                                currentPlaybackTime: FiniteDuration,
                                                lastProgressTime: Option[FiniteDuration]):
    /**
      * Checks whether a current playlist exists and has been activated.
      *
      * @return a flag if a current playlist exists
      */
    def hasActivePlaylist: Boolean =
      playerState.playlist.pendingSongs.nonEmpty && playerState.playlistActivated
  end AudioPlayerControllerState

  /** Constant for the initial state of an actor instance. */
  private val InitialControllerState = AudioPlayerControllerState(
    playerState = AudioPlayerState.Initial,
    audioPlayerActor = null,
    playerActorCount = 0,
    currentKillSwitch = None,
    currentBytesProcessed = 0,
    currentPlaybackTime = 0.seconds,
    lastProgressTime = None
  )

  /**
    * A data class that holds the configuration settings supported by an actor
    * instance. An instance of this class must be passed to the factory to
    * create a new actor instance.
    *
    * The actor registers itself at the system message bus and listens for
    * audio player commands. Since the registration happens asynchronously,
    * there is a race condition, and command sent immediately after the actor
    * creation could be missed. To work around this, a [[Promise]] can be
    * passed. It is completed, as soon as the actor is ready to process
    * commands on the message bus.
    *
    * @param messageBus         the system message bus
    * @param archiveService     the service to access the media archive
    * @param configService      the service to access the platform config
    * @param audioStreamFactory the factory to obtain audio streams
    * @param promiseReady       a promise to indicate that the actor is ready
    * @param playlistService    the service to manage playlists
    * @param audioPlayerFactory the factory to create audio player actors
    */
  final case class Config(messageBus: MessageBus,
                          archiveService: ArchiveService,
                          configService: ConfigService,
                          audioStreamFactory: AsyncAudioStreamFactory,
                          promiseReady: Promise[Unit],
                          playlistService: PlaylistService[Playlist, String] = PlaylistServiceImpl,
                          audioPlayerFactory: AudioPlayerActor.Factory = AudioPlayerActor.newInstance)

  /**
    * A factory trait for creating new actor instances.
    */
  trait Factory:
    /**
      * Creates the behavior for a new instance of 
      * [[AudioPlayerControllerActor]]. When the instance is actually created,
      * the actor is properly set up to receive commands via the system message 
      * bus.
      *
      * @param config the configuration for the actor instance
      * @return the behavior for the new [[AudioPlayerControllerActor]] instance
      */
    def apply(config: Config):
    Behavior[AudioPlayerControllerCommand]

  /** A default factory for creating new instances. */
  final val newInstance: Factory = (config: Config) =>
    val behavior = Behaviors.setup[AudioPlayerInternalControllerCommand]: context =>

      /**
        * Returns the receiver function that listens for audio player commands
        * on the system message bus. The commands are just forwarded to the 
        * actor instance.
        *
        * @return the message bus receiver function
        */
      def createMessageBusReceiver(): classics.Actor.Receive =
        case c: AudioPlayerCommands =>
          context.self ! c

      import config.*
      val messageBusID = messageBus.registerListener(createMessageBusReceiver())
      promiseReady.success(()) // Indicate that command processing is active.
      val audioPlayerConfig = AudioPlayerActor.Config(
        archiveService = archiveService,
        playlistCallback = event => context.self ! event,
        progressCallback = chunk => context.self ! chunk,
        audioStreamFactory = audioStreamFactory,
        initPlaybackState = PausePlaybackStage.PlaybackState.PlaybackPaused
      )

      /**
        * The command handler function for this actor instance.
        *
        * @param state the current state of the actor
        * @return the updated behavior
        */
      def handleControllerCommand(state: AudioPlayerControllerState): Behavior[AudioPlayerInternalControllerCommand] =
        /**
          * Returns an [[AudioPlayerControllerState]] based on the current
          * state and makes sure that the audio player actor has been
          * initialized. It is created if necessary. If specified, a reset of
          * the player engine is performed by stopping the current player actor
          * and creating a new one.
          *
          * @param resetEngine flag whether the engine should be reset
          * @return the state with a guaranteed player actor
          */
        def stateWithPlayerActor(resetEngine: Boolean = false): AudioPlayerControllerState =
          val currentPlayerActor = if resetEngine then
            assert(state.audioPlayerActor != null)
            state.audioPlayerActor ! AudioPlayerActor.AudioPlayerCommand.Stop
            null
          else
            state.audioPlayerActor
          if currentPlayerActor != null then
            state
          else
            val nextCount = state.playerActorCount + 1
            context.log.info("Creating {}. audio player actor.", nextCount)
            val currentConfig = if state.playerState.playbackActive then
              audioPlayerConfig.copy(initPlaybackState = PausePlaybackStage.PlaybackState.PlaybackPossible)
            else
              audioPlayerConfig
            val playerActor = context.spawn(audioPlayerFactory(currentConfig), AudioPlayerActorName + nextCount)
            state.copy(audioPlayerActor = playerActor, playerActorCount = nextCount)

        /**
          * Publishes the state contained in the given controller state on the
          * message bus, so that interested components receive the update.
          *
          * @param controllerState the state to be published
          * @return the same controller state
          */
        def publishPlayerState(controllerState: AudioPlayerControllerState): AudioPlayerControllerState =
          messageBus.publish(controllerState.playerState)
          controllerState

        /**
          * Handles a SetPlaylist command. The songs of the playlist are passed
          * to the audio player actor. The current song is passed with the
          * position offset, so that playback can be resumed at a specific
          * position.
          *
          * @param cmd             the command
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handleSetPlaylist(cmd: AudioPlayerCommands.SetPlaylist,
                              controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          val nextState = stateWithPlayerActor(resetEngine = state.hasActivePlaylist)
          cmd.playlist.pendingSongs.zipWithIndex foreach : (song, idx) =>
            val optOffset =
              if idx == 0 && cmd.positionOffset != 0 then Some(cmd.positionOffset)
              else None
            nextState.audioPlayerActor ! AudioPlayerActor.AudioPlayerCommand.AppendToPlaylist(song, optOffset)
          if cmd.closePlaylist then
            nextState.audioPlayerActor ! AudioPlayerActor.AudioPlayerCommand.ClosePlaylist
          val seqNo =
            if playlistService.playlistEquals(controllerState.playerState.playlist, cmd.playlist)
            then controllerState.playerState.playlistSeqNo
            else playlistService.incrementPlaylistSeqNo(controllerState.playerState.playlistSeqNo)
          val updatedState = nextState.copy(
            playerState = controllerState.playerState.copy(
              playlist = cmd.playlist,
              playlistSeqNo = seqNo,
              playlistClosed = cmd.closePlaylist,
              playlistActivated = cmd.playlist.pendingSongs.nonEmpty
            ),
            currentBytesProcessed = cmd.positionOffset,
            currentPlaybackTime = cmd.timeOffset,
            lastProgressTime = None
          )
          publishPlayerState(updatedState)
          handleControllerCommand(updatedState)

        /**
          * Handles an AppendPlaylist command. The songs referenced by the
          * command are added to the pending list of the current playlist. If
          * this playlist has already been activated, the new songs are also
          * passed to the audio player actor. If the playlist has already been
          * closed, the command is ignored.
          *
          * @param cmd             the command
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handleAppendPlaylist(cmd: AudioPlayerCommands.AppendPlaylist,
                                 controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          if controllerState.playerState.playlistClosed then
            Behaviors.same
          else
            if controllerState.playerState.playlistActivated then
              cmd.songIDs foreach (song =>
                controllerState.audioPlayerActor ! AudioPlayerActor.AudioPlayerCommand.AppendToPlaylist(song))
            val updatedState = controllerState.copy(
              playerState = controllerState.playerState.copy(
                playlist = controllerState.playerState.playlist.copy(
                  pendingSongs = controllerState.playerState.playlist.pendingSongs ++ cmd.songIDs
                ),
                playlistSeqNo = playlistService.incrementPlaylistSeqNo(
                  controllerState.playerState.playlistSeqNo)
              )
            )
            publishPlayerState(updatedState)
            handleControllerCommand(updatedState)

        /**
          * Handles a chunk of audio data that has been played. The chunk is
          * sent to this actor by the audio player actor via the progress
          * callback. Its byte size and duration are added to the counters in
          * the controller state. A progress event on the message bus is only
          * published if at least one second has passed since the last
          * published progress event.
          *
          * @param chunk           the chunk of audio data that has been played
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handlePlaybackProgress(chunk: LineWriterStage.PlayedAudioChunk,
                                   controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          val updatedBytes = controllerState.currentBytesProcessed + chunk.size
          val updatedTime = controllerState.currentPlaybackTime + chunk.duration
          val publishProgress = controllerState.lastProgressTime match
            case None => true
            case Some(time) => updatedTime - time >= 1.second
          val updatedState = controllerState.copy(
            currentBytesProcessed = updatedBytes,
            currentPlaybackTime = updatedTime,
            lastProgressTime = if publishProgress then Some(updatedTime)
            else controllerState.lastProgressTime
          )
          if publishProgress then
            messageBus.publish(PlaybackProgress(updatedBytes, updatedTime))
          handleControllerCommand(updatedState)

        /**
          * Checks whether the file with the given ID is the current song in
          * the playlist.
          *
          * @param mediaFileID     the file ID
          * @param controllerState the current state
          * @return a flag whether this is the current song
          */
        def isCurrentMediaFile(mediaFileID: String, controllerState: AudioPlayerControllerState): Boolean =
          playlistService.currentSong(controllerState.playerState.playlist).contains(mediaFileID)

        /**
          * Handles an event about a completed media file by delegating to the
          * common handler for the termination of media file playback.
          *
          * @param mediaFileID     the ID of the file whose playback has completed
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handleMediaFileEnded(mediaFileID: String,
                                 controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          handleMediaFilePlaybackFinished(mediaFileID, controllerState)

        /**
          * Handles an event about a failed media file by delegating to the
          * common handler for the termination of media file playback.
          *
          * @param mediaFileID     the ID of the file whose playback has failed
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handleMediaFileFailed(mediaFileID: String,
                                  controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          handleMediaFilePlaybackFinished(mediaFileID, controllerState)

        /**
          * Common handler for events about the termination of the playback of
          * a media file; this can happen either because playback completed or
          * because it failed.
          *
          * @param mediaFileID     the ID of the affected file
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handleMediaFilePlaybackFinished(mediaFileID: String,
                                            controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          if isCurrentMediaFile(mediaFileID, controllerState) then
            val nextPlaylist = playlistService.moveForwards(controllerState.playerState.playlist).get
            val nextState = controllerState.copy(
              playerState = controllerState.playerState.copy(
                playlist = nextPlaylist,
                playbackActive =
                  if playlistService.currentSong(nextPlaylist).isEmpty
                    && controllerState.playerState.playlistClosed
                    && controllerState.playerState.playbackActive
                  then false
                  else controllerState.playerState.playbackActive
              ),
              currentBytesProcessed = 0,
              currentPlaybackTime = 0.seconds,
              lastProgressTime = None
            )
            publishPlayerState(nextState)
            handleControllerCommand(nextState)
          else
            Behaviors.same

        /**
          * Handles an event about playback start on a new media file. The
          * provided [[KillSwitch]] needs to be recorded, so that this source
          * can be skipped.
          *
          * @param mediaFileID     the ID of the affected file
          * @param killSwitch      the [[KillSwitch]]
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handleMediaFileStarted(mediaFileID: String,
                                   killSwitch: KillSwitch,
                                   controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          if isCurrentMediaFile(mediaFileID, controllerState) then
            val nextState = controllerState.copy(currentKillSwitch = Some(killSwitch))
            handleControllerCommand(nextState)
          else
            Behaviors.same

        /**
          * Dispatches events received from the audio player actor to the
          * specific handler functions.
          *
          * @param event           the event to be handled
          * @param controllerState the current controller state
          * @return the updated behavior
          */
        def handlePlaylistEvent(event: AudioPlayerActor.PlaylistEvent,
                                controllerState: AudioPlayerControllerState):
        Behavior[AudioPlayerInternalControllerCommand] =
          event match
            case AudioPlayerActor.PlaylistEvent.MediaFileEnded(mediaFileID) =>
              handleMediaFileEnded(mediaFileID, controllerState)
            case AudioPlayerActor.PlaylistEvent.MediaFileFailed(mediaFileID, _) =>
              handleMediaFileFailed(mediaFileID, controllerState)
            case AudioPlayerActor.PlaylistEvent.MediaFileStarted(mediaFileID, killSwitch) =>
              handleMediaFileStarted(mediaFileID, killSwitch, controllerState)

        Behaviors.receiveMessage:
          case Stop =>
            messageBus.removeListener(messageBusID)
            Behaviors.stopped

          case cmd: AudioPlayerCommands.SetPlaylist =>
            handleSetPlaylist(cmd, state)

          case cmd: AudioPlayerCommands.AppendPlaylist =>
            handleAppendPlaylist(cmd, state)

          case AudioPlayerCommands.StartAudioPlayback =>
            if !state.playerState.playbackActive then
              val nextState = stateWithPlayerActor()
              nextState.audioPlayerActor ! AudioPlayerActor.AudioPlayerCommand.StartPlayback
              val updatedState = nextState.copy(
                playerState = state.playerState.copy(playbackActive = true, playlistActivated = true)
              )
              publishPlayerState(updatedState)
              handleControllerCommand(updatedState)
            else
              Behaviors.same

          case AudioPlayerCommands.StopAudioPlayback =>
            if state.playerState.playbackActive then
              state.audioPlayerActor ! AudioPlayerActor.AudioPlayerCommand.StopPlayback
              val updatedState = state.copy(
                playerState = state.playerState.copy(playbackActive = false)
              )
              publishPlayerState(updatedState)
              handleControllerCommand(updatedState)
            else
              Behaviors.same

          case AudioPlayerCommands.SkipCurrentSource =>
            state.currentKillSwitch match
              case Some(ks) =>
                ks.shutdown()
                handleControllerCommand(state.copy(currentKillSwitch = None))
              case None =>
                Behaviors.same

          case chunk: LineWriterStage.PlayedAudioChunk =>
            handlePlaybackProgress(chunk, state)

          case ev: AudioPlayerActor.PlaylistEvent =>
            handlePlaylistEvent(ev, state)

      messageBus.publish(AudioPlayerState.Initial)
      handleControllerCommand(InitialControllerState)
    behavior.narrow[AudioPlayerControllerCommand]
end AudioPlayerControllerActor
