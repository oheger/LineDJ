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

package de.oliver_heger.linedj.platform.audio.actors

import de.oliver_heger.linedj.platform.audio.actors.PlayerManagerActor.*
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.player.engine.{AudioStreamFactory, PlaybackContextFactory}
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object PlayerManagerActor:
  /**
    * Type definition for a function that creates a player asynchronously and
    * returns a state object with all the information required to manage the
    * lifecycle of this player.
    */
  type PlayerCreationFunc[STATE] = () => Future[STATE]

  /**
    * The base trait for commands handled by this actor.
    */
  sealed trait PlayerManagementCommand

  /**
    * A message class that adds the given [[PlaybackContextFactory]] instances
    * to the managed audio player.
    *
    * @param factories the [[PlaybackContextFactory]] objects to add
    */
  case class AddPlaybackContextFactories(factories: List[PlaybackContextFactory]) extends PlayerManagementCommand

  /**
    * A message class that removes the given [[PlaybackContextFactory]]
    * instances from the managed audio player.
    *
    * @param factories the [[PlaybackContextFactory]] objects to remove
    */
  case class RemovePlaybackContextFactories(factories: List[PlaybackContextFactory])
    extends PlayerManagementCommand

  /**
    * A message class that adds the given [[AudioStreamFactory]] instances to
    * the managed audio player.
    *
    * @param factories the [[AudioStreamFactory]] object to add
    */
  case class AddAudioStreamFactories(factories: List[AudioStreamFactory]) extends PlayerManagementCommand

  /**
    * A message class that removes the given [[AudioStreamFactory]] instances
    * from the managed audio player.
    *
    * @param factories the [[AudioStreamFactory]] objects to remove
    */
  case class RemoveAudioStreamFactories(factories: List[AudioStreamFactory]) extends PlayerManagementCommand

  /**
    * A message class that publishes a specific message on the message bus
    * after the managed player has been registered. This is required to safely
    * communicate with message receivers that are registered during the
    * asynchronous creation of the player.
    *
    * @param message the message to be published
    */
  case class PublishAfterCreation(message: Any) extends PlayerManagementCommand

  /**
    * A message triggering the closing of the managed audio player. When this
    * operation is complete the specified client actor receives a [[CloseAck]]
    * message.
    *
    * @param client  the client to be notified when close is complete
    * @param timeout the timeout for the operation
    */
  case class Close(client: ActorRef[CloseAck],
                   timeout: Timeout) extends PlayerManagementCommand

  /**
    * An internal message the actor sends to itself when the creation of the
    * player failed.
    *
    * @param cause the cause of the failure
    */
  private case class PlayerCreationFailed(cause: Throwable) extends PlayerManagementCommand

  /**
    * An internal message the actor sends to itself when the player has been
    * closed.
    *
    * @param result the result of the close operation
    */
  private case class PlayerClosed(result: Try[Unit]) extends PlayerManagementCommand

  /**
    * A message sent by this actor as an acknowledge when the audio player has
    * been closed.
    *
    * @param result the result of the close operation
    */
  case class CloseAck(result: Try[Unit])
end PlayerManagerActor

/**
  * A base trait for an actor implementation that manages the asynchronous
  * creation and initialization of a player instance.
  *
  * The lifecycle of a player is rather tricky with regards to threading:
  * [[PlaybackContextFactory]] instances can arrive at any time on the OSGi
  * thread; the creation of the player itself is an asynchronous operation, and
  * when required services go away, the player has to be closed again (even if
  * its creation may not yet be complete). By making use of the actor model,
  * messing with threads manually can be avoided.
  *
  * This trait handles the management of a player lifecycle in a generic way. It
  * abstracts away from the concrete player that is managed and the steps to
  * perform to setup and close it; this has to be dealt with in concrete
  * implementations.
  *
  * @tparam STATE the state to manage on behalf of the player
  * @tparam EVENT the type of events generated by the player
  */
trait PlayerManagerActor[STATE, EVENT]:
  /**
    * An internal message the actor sends to itself when the asynchronous
    * creation of the player completed successfully.
    *
    * @param state the state to be managed
    */
  private case class PlayerCreated(state: STATE) extends PlayerManagementCommand

  /**
    * Returns the behavior of an actor instance to manage the lifecycle of a
    * player of the supported type.
    *
    * @param messageBus the central message bus
    * @param creator    the function to create the player
    * @return the behavior to create an actor instance
    */
  def behavior(messageBus: MessageBus)(creator: PlayerCreationFunc[STATE]): Behavior[PlayerManagementCommand] =
    Behaviors.setup[PlayerManagementCommand] { context =>
      implicit val ec: ExecutionContext = context.system.executionContext

      creator() onComplete { result =>
        val message = result match
          case Success(state) => PlayerCreated(state)
          case Failure(exception) => PlayerCreationFailed(exception)
        context.self ! message
      }

      playerCreationPending(messageBus, List.empty, List.empty, List.empty, None)
    }

  /**
    * Obtains the managed [[PlayerControl]] from the given state object. Via
    * this function the generic trait interacts with the player instance.
    *
    * @param state the player state
    * @return the [[PlayerControl]] representing the managed player
    */
  protected def getPlayer(state: STATE): PlayerControl[EVENT]

  /**
    * Callback that gets invoked after the creation function succeeded. A
    * concrete implementation can do the necessary initialization tasks here,
    * such as registrations on the message bus, etc.
    *
    * @param state the state received from the creation function
    * @return the updated state
    */
  protected def onInit(state: STATE): STATE = state

  /**
    * Callback that gets invoked if the creation function returns a failure. A
    * concrete implementation can perform some corresponding action. This base
    * implementation is empty.
    *
    * @param cause the cause of the failure
    */
  protected def onInitFailure(cause: Throwable): Unit = {}

  /**
    * Callback that gets invoked when the player is closed. A concrete
    * implementation has to do the necessary clean up here based on the state
    * provided.
    *
    * @param state the state of the managed player
    */
  protected def onClose(state: STATE): Unit

  /**
    * A message handler function that is active while the managed player is
    * created asynchronously. In this state, the actor waits for the completion
    * of the creation function. Changes on the set of
    * [[PlaybackContextFactory]] objects and messages to publish on the message
    * bus are tracked. It is also possible that the player is already closed
    * before the creation is done.
    *
    * @param messageBus       the central message bus
    * @param contextFactories the current list of playback context factories
    * @param streamFactories  the current list of audio stream factories
    * @param messages         the message to publish on the bus
    * @param optClose         indicates whether the player has been closed
    * @return the behavior waiting for the player creation
    */
  private def playerCreationPending(messageBus: MessageBus,
                                    contextFactories: List[PlaybackContextFactory],
                                    streamFactories: List[AudioStreamFactory],
                                    messages: List[Any],
                                    optClose: Option[Close]): Behavior[PlayerManagementCommand] =
    Behaviors.receivePartial:
      case (_, AddPlaybackContextFactories(newFactories)) =>
        playerCreationPending(messageBus, newFactories ::: contextFactories, streamFactories, messages, optClose)

      case (_, RemovePlaybackContextFactories(removeFactories)) =>
        playerCreationPending(messageBus, contextFactories filterNot (factory => removeFactories.contains(factory)),
          streamFactories, messages, optClose)

      case (_, AddAudioStreamFactories(newFactories)) =>
        playerCreationPending(messageBus, contextFactories, newFactories ::: streamFactories, messages, optClose)

      case (_, RemoveAudioStreamFactories(removeFactories)) =>
        val newStreamFactories = streamFactories filterNot removeFactories.contains
        playerCreationPending(messageBus, contextFactories, newStreamFactories, messages, optClose)

      case (_, PublishAfterCreation(message)) =>
        playerCreationPending(messageBus, contextFactories, streamFactories, message :: messages, optClose)

      case (context, c: PlayerCreated) =>
        optClose match
          case None =>
            val initState = onInit(c.state)
            val player = getPlayer(initState)
            contextFactories foreach { factory =>
              player.addPlaybackContextFactory(factory)
            }
            streamFactories foreach player.addAudioStreamFactory
            val listener = addEventListener(context, messageBus, player)
            messages.reverse.foreach(messageBus.publish)
            context.log.info("Player was created successfully.")
            context.log.debug("Player state is: {}.", initState)
            playerActive(messageBus, initState, listener)

          case Some(close) =>
            closePlayer(context, getPlayer(c.state), close)

      case (context, PlayerCreationFailed(cause)) =>
        context.log.error("Creation of player failed.", cause)
        onInitFailure(cause)
        optClose match
          case None =>
            playerFailure(cause)
          case Some(close) =>
            close.client ! CloseAck(Failure(cause))
            Behaviors.stopped

      case (_, close: Close) =>
        playerCreationPending(messageBus, contextFactories, streamFactories, messages, Some(close))

  /**
    * A message handler function for the state in which the managed player is
    * active. The function deals with changes on [[PlaybackContextFactory]]
    * objects and messages to publish. The actor remains in this state until
    * the player is closed.
    *
    * @param messageBus    the central message bus
    * @param state         the current state
    * @param eventListener the event listener registered at the player
    * @return the behavior for the active player
    */
  private def playerActive(messageBus: MessageBus, state: STATE, eventListener: ActorRef[EVENT]):
  Behavior[PlayerManagementCommand] =
    Behaviors.receivePartial:
      case (_, AddPlaybackContextFactories(factories)) =>
        factories foreach getPlayer(state).addPlaybackContextFactory
        Behaviors.same

      case (_, RemovePlaybackContextFactories(factories)) =>
        factories foreach getPlayer(state).removePlaybackContextFactory
        Behaviors.same

      case (_, AddAudioStreamFactories(factories)) =>
        factories foreach getPlayer(state).addAudioStreamFactory
        Behaviors.same

      case (_, RemoveAudioStreamFactories(factories)) =>
        factories foreach getPlayer(state).removeAudioStreamFactory
        Behaviors.same

      case (_, PublishAfterCreation(message)) =>
        messageBus publish message
        Behaviors.same

      case (context, close: Close) =>
        getPlayer(state).removeEventListener(eventListener)
        onClose(state)
        closePlayer(context, getPlayer(state), close)

  /**
    * A message handler function that waits until the player has been closed.
    * Afterwards, the client triggering this operation is notified.
    *
    * @param client the client of the close operation
    * @return the behavior waiting for close
    */
  private def closePending(client: ActorRef[CloseAck]): Behavior[PlayerManagementCommand] =
    Behaviors.receiveMessagePartial:
      case PlayerClosed(result) =>
        client ! CloseAck(result)
        Behaviors.stopped

  /**
    * A message handler function that becomes active when the player could not
    * be created. In this state, the actor only waits until it receives a close
    * command.
    *
    * @param cause the cause for the failed player creation
    * @return the behavior for the error state
    */
  private def playerFailure(cause: Throwable): Behavior[PlayerManagementCommand] =
    Behaviors.receiveMessagePartial:
      case Close(client, _) =>
        client ! CloseAck(Failure(cause))
        Behaviors.stopped

  /**
    * Closes the managed player and waits until this operation is complete.
    * The result is then passed as a message to Self.
    *
    * @param context the actor context
    * @param player  the managed player
    * @param close   the [[Close]] command triggering this action
    * @return the behavior waiting for the close to complete
    */
  private def closePlayer(context: ActorContext[PlayerManagementCommand],
                          player: PlayerControl[EVENT],
                          close: Close): Behavior[PlayerManagementCommand] =
    implicit val ec: ExecutionContext = context.executionContext
    implicit val timeout: Timeout = close.timeout
    player.close() map (_ => ()) onComplete { result =>
      context.self ! PlayerClosed(result)
    }
    closePending(close.client)

  /**
    * Adds an event listener to the managed player that publishes all received
    * events on the message bus.
    *
    * @param context    the actor context
    * @param messageBus the message bus
    * @param player     the player
    * @return the event listener actor ref
    */
  private def addEventListener(context: ActorContext[PlayerManagementCommand],
                               messageBus: MessageBus,
                               player: PlayerControl[EVENT]): ActorRef[EVENT] =
    val listenerBehavior = Behaviors.receiveMessage[EVENT]:
      event =>
        messageBus publish event
        Behaviors.same
    val listener = context.spawn(listenerBehavior, "audioPlayerEventListener")

    player.addEventListener(listener)
    listener
