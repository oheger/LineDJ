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
import de.oliver_heger.linedj.platform.audio2.{AudioPlayerCommands, AudioPlayerState}
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.startup.ConfigService
import de.oliver_heger.linedj.player.engine.stream.LineWriterStage
import org.apache.pekko.actor as classics
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.stream.KillSwitch

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
    * @param playerState       the state of audio player
    * @param audioPlayerActor  the actor managing the audio player engine
    * @param playerActorCount  a counter to generate unique actor names
    * @param currentKillSwitch stores a kill switch to cancel the current file
    */
  private case class AudioPlayerControllerState(playerState: AudioPlayerState,
                                                audioPlayerActor: ActorRef[AudioPlayerActor.AudioPlayerCommand],
                                                playerActorCount: Int,
                                                currentKillSwitch: Option[KillSwitch]):
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
    currentKillSwitch = None
  )

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
      * @param messageBus         the system message bus
      * @param archiveService     the service to access the media archive
      * @param configService      the service to access the platform config
      * @param playlistService    the service to manage playlists
      * @param audioPlayerFactory the factory to create audio player actors
      * @return the behavior for the new [[AudioPlayerControllerActor]] instance
      */
    def apply(messageBus: MessageBus,
              archiveService: ArchiveService,
              configService: ConfigService,
              playlistService: PlaylistService[Playlist, String] = PlaylistServiceImpl,
              audioPlayerFactory: AudioPlayerActor.Factory = AudioPlayerActor.newInstance):
    Behavior[AudioPlayerControllerCommand]

  /** A default factory for creating new instances. */
  final val newInstance: Factory = (messageBus: MessageBus,
                                    archiveService: ArchiveService,
                                    configService: ConfigService,
                                    playlistService: PlaylistService[Playlist, String],
                                    audioPlayerFactory: AudioPlayerActor.Factory) =>
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

      val messageBusID = messageBus.registerListener(createMessageBusReceiver())
      val audioPlayerConfig = AudioPlayerActor.Config(
        archiveService = archiveService,
        playlistCallback = event => context.self ! event,
        progressCallback = null
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
            val playerActor = context.spawn(audioPlayerFactory(audioPlayerConfig), AudioPlayerActorName + nextCount)
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
            )
          )
          publishPlayerState(updatedState)
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
              )
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

          case ev: AudioPlayerActor.PlaylistEvent =>
            handlePlaylistEvent(ev, state)

          case _ =>
            Behaviors.same

      handleControllerCommand(InitialControllerState)
    behavior.narrow[AudioPlayerControllerCommand]
end AudioPlayerControllerActor
