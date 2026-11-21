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

package de.oliver_heger.linedj.platform.radio.impl

import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.radio.{RadioPlayerCommands, RadioPlayerState}
import de.oliver_heger.linedj.player.server.model.RadioModel
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * A module providing an actor implementation that manages the current state
  * of the radio player engine and listens for commands to change the playback
  * status. It also receives messages via a [[RadioMessageActor]] about changes
  * in the radio player state. On receiving such a message, it updates the
  * managed playback state accordingly. Whenever there is a change in the
  * state, it publishes the newest instance on the system message bus.
  */
object RadioPlayerActor:
  /**
    * The name of the child actor to retrieve radio messages from the server.
    */
  private val RadioMessageActorName = "radioMessageActor"

  /**
    * An enumeration defining the commands supported by this actor.
    */
  enum Command:
    /**
      * A command to shut down an actor instance. Before the actor terminates,
      * it releases all resources in use.
      */
    case Stop

  /**
    * An internal command with the radio sources loaded from the server.
    *
    * @param sources the available radio sources
    */
  private final case class SourcesLoaded(sources: RadioModel.RadioSources)

  /**
    * Alias for the type of all commands that are handled internally by this
    * actor implementations. Not all of these commands are visible to the
    * outside.
    */
  private type InternalCommand = Command | RadioPlayerCommands | RadioModel.RadioMessage | SourcesLoaded

  /** The initial, empty state of the radio player. */
  private val InitialState = RadioPlayerState(
    radioSources = List.empty,
    optCurrentSourceID = None,
    optReplacementID = None,
    playbackEnabled = false,
    optTitle = None
  )

  /**
    * A factory trait for creating new instances of this actor.
    */
  trait Factory:
    /**
      * Returns a [[Behavior]] to create a new actor instance for the provided
      * parameters.
      *
      * @param serverUri           the URI of the player server
      * @param playerService       the service to interact with the server
      * @param messageBus          the system message bus
      * @param messageActorFactory the factory to create a message actor
      * @return the behavior of the new instance
      */
    def apply(serverUri: String,
              playerService: PlayerService,
              messageBus: MessageBus,
              messageActorFactory: RadioMessageActor.Factory = RadioMessageActor.newInstance): Behavior[Command]

  /** A default factory for creating new actor instances. */
  final val newInstance: Factory =
    (serverUri: String,
     playerService: PlayerService,
     messageBus: MessageBus,
     messageActorFactory: RadioMessageActor.Factory) =>
      setUpActor(serverUri, playerService, messageBus, messageActorFactory).narrow

  /**
    * Creates the behavior of a new actor instance.
    *
    * @param serverUri           the URI of the player server
    * @param playerService       the service to interact with the server
    * @param messageBus          the system message bus
    * @param messageActorFactory the factory to create a message actor
    * @return the behavior of the new instance
    */
  private def setUpActor(serverUri: String,
                         playerService: PlayerService,
                         messageBus: MessageBus,
                         messageActorFactory: RadioMessageActor.Factory): Behavior[InternalCommand] =
    Behaviors.setup: context =>
      given ExecutionContext = context.executionContext

      /**
        * Returns the receiver function that listens for radio player commands
        * on the system message bus and forwards them to this actor.
        *
        * @return the message bus receiver function
        */
      def createMessageBusReceiver(): Receive =
        case cmd: RadioPlayerCommands => context.self ! cmd

      val messageBusID = messageBus.registerListener(createMessageBusReceiver())
      val messageActor = context.spawn(
        messageActorFactory(serverUri, context.self.narrow[RadioModel.RadioMessage]),
        RadioMessageActorName
      )

      playerService.radioSources.onComplete:
        case Success(sources) => context.self ! SourcesLoaded(sources)
        case Failure(exception) => context.log.error("Could not query the radio sources.", exception)

      /**
        * Returns the behavior for handling commands based on the current state
        * of the radio player.
        *
        * @param state the current player state
        * @return the behavior for processing commands
        */
      def handleCommand(state: RadioPlayerState): Behavior[InternalCommand] =
        Behaviors.receiveMessage:
          case RadioPlayerCommands.StartPlayback =>
            playerService.startPlayback()
            handleCommand(state)

          case RadioPlayerCommands.StopPlayback =>
            playerService.stopPlayback()
            handleCommand(state)

          case RadioPlayerCommands.SelectRadioSource(sourceID) =>
            playerService.switchToSource(sourceID)
            handleCommand(state)

          case SourcesLoaded(sources) =>
            updateState(state.copy(radioSources = sources.sources))

          case message: RadioModel.RadioMessage =>
            val nextState = updateRadioState(message, state)
            if nextState == state then Behaviors.same else updateState(nextState)

          case Command.Stop =>
            messageBus.removeListener(messageBusID)
            messageActor ! RadioMessageActor.Stop
            Behaviors.stopped

      /**
        * Publishes the given state on the message bus and continues processing
        * commands with it.
        *
        * @param state the new state
        * @return the behavior for processing further commands
        */
      def updateState(state: RadioPlayerState): Behavior[InternalCommand] =
        messageBus.publish(state)
        handleCommand(state)

      handleCommand(InitialState)

  /**
    * Updates the given player state based on a message received from the
    * message actor. Unknown message types leave the state unchanged.
    *
    * @param message the received message
    * @param state   the current player state
    * @return the updated state
    */
  private def updateRadioState(message: RadioModel.RadioMessage,
                               state: RadioPlayerState): RadioPlayerState =
    message.messageType match
      case RadioModel.MessageTypeSourceSelected =>
        state.copy(optCurrentSourceID = Some(message.payload))

      case RadioModel.MessageTypeSourceChanged =>
        val optSourceID =
          if state.optReplacementID.contains(message.payload) then state.optCurrentSourceID
          else Some(message.payload)
        state.copy(optCurrentSourceID = optSourceID, playbackEnabled = true)

      case RadioModel.MessageTypeReplacementStart =>
        state.copy(optReplacementID = Some(message.payload), playbackEnabled = true)

      case RadioModel.MessageTypeReplacementEnd =>
        state.copy(optCurrentSourceID = Some(message.payload), optReplacementID = None, playbackEnabled = true)

      case RadioModel.MessageTypePlaybackStopped =>
        state.copy(playbackEnabled = false)

      case RadioModel.MessageTypeTitleInfo =>
        state.copy(optTitle = Option(message.payload).filter(_.nonEmpty))

      case _ => state
