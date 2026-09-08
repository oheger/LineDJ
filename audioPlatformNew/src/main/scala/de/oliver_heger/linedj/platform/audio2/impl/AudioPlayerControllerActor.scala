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
    * @param playerState      the state of audio player
    * @param audioPlayerActor the actor managing the audio player engine
    */
  private case class AudioPlayerControllerState(playerState: AudioPlayerState,
                                                audioPlayerActor: ActorRef[AudioPlayerActor.AudioPlayerCommand])

  /** Constant for the initial state of an actor instance. */
  private val InitialControllerState = AudioPlayerControllerState(
    playerState = AudioPlayerState.Initial,
    audioPlayerActor = null
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
    Behaviors.setup[AudioPlayerControllerCommand]: context =>

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
        playlistCallback = null,
        progressCallback = null
      )

      /**
        * The command handler function for this actor instance.
        *
        * @param state the current state of the actor
        * @return the updated behavior
        */
      def handleControllerCommand(state: AudioPlayerControllerState): Behavior[AudioPlayerInternalControllerCommand] =
        def stateWithPlayerActor(): AudioPlayerControllerState =
          if state.audioPlayerActor != null then
            state
          else
            val playerActor = context.spawn(audioPlayerFactory(audioPlayerConfig), AudioPlayerActorName)
            state.copy(audioPlayerActor = playerActor)

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
          val nextState = stateWithPlayerActor()
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

        Behaviors.receiveMessage:
          case Stop =>
            messageBus.removeListener(messageBusID)
            Behaviors.stopped

          case cmd: AudioPlayerCommands.SetPlaylist =>
            handleSetPlaylist(cmd, state)

          case _ =>
            Behaviors.same

      handleControllerCommand(InitialControllerState).narrow
end AudioPlayerControllerActor
