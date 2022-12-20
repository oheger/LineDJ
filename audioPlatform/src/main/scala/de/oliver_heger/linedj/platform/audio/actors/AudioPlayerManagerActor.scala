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

package de.oliver_heger.linedj.platform.audio.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import de.oliver_heger.linedj.platform.comm.MessageBus
import de.oliver_heger.linedj.platform.comm.ServiceDependencies.{RegisterService, ServiceDependency, UnregisterService}
import de.oliver_heger.linedj.player.engine.PlaybackContextFactory
import de.oliver_heger.linedj.player.engine.facade.AudioPlayer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * An actor implementation that manages the asynchronous creation and
  * initialization of an audio player instance.
  *
  * The lifecycle of an audio player is rather tricky with regards to
  * threading: [[PlaybackContextFactory]] instances can arrive at any time on
  * the OSGi thread; the creation of the player itself is an asynchronous
  * operation, and when required services go away, the player has to be closed
  * again (even if its creation may not yet be complete). This actor
  * implementation handles these aspects; by making use of the actor model,
  * messing with threads manually can be avoided.
  */
object AudioPlayerManagerActor {
  /**
    * Type definition of a function that can create an [[AudioPlayer]] instance
    * asynchronously.
    */
  type ControllerCreationFunc = () => Future[AudioPlayerController]

  /**
    * The base trait for commands handled by this actor.
    */
  sealed trait AudioPlayerManagementCommand

  /**
    * A message class that adds the given [[PlaybackContextFactory]] instances
    * to the managed audio player.
    *
    * @param factories the [[PlaybackContextFactory]] objects to add
    */
  case class AddPlaybackContextFactories(factories: List[PlaybackContextFactory]) extends AudioPlayerManagementCommand

  /**
    * A message class that removes the given [[PlaybackContextFactory]]
    * instances from the managed audio player.
    *
    * @param factories the [[PlaybackContextFactory]] objects to remove
    */
  case class RemovePlaybackContextFactories(factories: List[PlaybackContextFactory])
    extends AudioPlayerManagementCommand

  /**
    * A message class that publishes a specific message on the message bus
    * after the [[AudioPlayerController]] has been registered. This is required
    * to prevent that messages to the controller get lost because it is created
    * asynchronously.
    *
    * @param message the message to be published
    */
  case class PublishToController(message: Any) extends AudioPlayerManagementCommand

  /**
    * A message triggering the closing of the managed audio player. When this
    * operation is complete the specified client actor receives a [[CloseAck]]
    * message.
    *
    * @param client  the client to be notified when close is complete
    * @param timeout the timeout for the operation
    */
  case class Close(client: ActorRef[CloseAck],
                   timeout: Timeout) extends AudioPlayerManagementCommand

  /**
    * An internal message the actor sends to itself when the player controller
    * could be created successfully.
    *
    * @param controller the reference to the controller
    */
  private case class ControllerCreated(controller: AudioPlayerController) extends AudioPlayerManagementCommand

  /**
    * An internal message the actor sends to itself when the creation of the
    * player controller fails.
    *
    * @param cause the cause of the failure
    */
  private case class ControllerCreationFailed(cause: Throwable) extends AudioPlayerManagementCommand

  /**
    * An internal message the actor sends to itself when the player has been
    * closed.
    *
    * @param result the result of the close operation
    */
  private case class PlayerClosed(result: Try[Unit]) extends AudioPlayerManagementCommand

  /**
    * A message sent by this actor as an acknowledge when the audio player has
    * been closed.
    *
    * @param result the result of the close operation
    */
  case class CloseAck(result: Try[Unit])

  /** The dependency for the controller registration. */
  private val ControllerDependency = ServiceDependency("lineDJ.audioPlayerController")

  /**
    * Returns the behavior of an actor instance to manage an audio player.
    *
    * @param messageBus the central message bus
    * @param creator    the function to create the audio player
    * @return the behavior to create an actor instance
    */
  def apply(messageBus: MessageBus)(creator: ControllerCreationFunc): Behavior[AudioPlayerManagementCommand] =
    Behaviors.setup[AudioPlayerManagementCommand] { context =>
      implicit val ec: ExecutionContext = context.system.executionContext

      creator() onComplete { result =>
        val message = result match {
          case Success(controller) => ControllerCreated(controller)
          case Failure(exception) => ControllerCreationFailed(exception)
        }
        context.self ! message
      }

      def controllerCreationPending(factories: List[PlaybackContextFactory],
                                    messages: List[Any],
                                    optClose: Option[Close]): Behavior[AudioPlayerManagementCommand] =
        Behaviors.receiveMessagePartial {
          case AddPlaybackContextFactories(newFactories) =>
            controllerCreationPending(newFactories ::: factories, messages, optClose)

          case RemovePlaybackContextFactories(removeFactories) =>
            controllerCreationPending(factories filterNot (factory => removeFactories.contains(factory)),
              messages, optClose)

          case PublishToController(message) =>
            controllerCreationPending(factories, message :: messages, optClose)

          case ControllerCreated(controller) =>
            optClose match {
              case None =>
                factories foreach { factory =>
                  controller.player.addPlaybackContextFactory(factory)
                }
                val listenerID = addEventListener(messageBus, controller)
                val registrationID = registerController(messageBus, controller)
                messages.reverse.foreach(messageBus.publish)
                context.log.info("AudioPlayerController was created successfully.")
                controllerActive(controller.player, registrationID, listenerID)

              case Some(close) =>
                closePlayer(controller.player, close)
            }

          case ControllerCreationFailed(cause) =>
            context.log.error("Creation of AudioPlayerController failed.", cause)
            optClose match {
              case None =>
                playerFailure(cause)
              case Some(close) =>
                close.client ! CloseAck(Failure(cause))
                Behaviors.stopped
            }

          case close: Close =>
            controllerCreationPending(factories, messages, Some(close))
        }

      def controllerActive(player: AudioPlayer, messageBusRegistration: Int, eventListener: Int):
      Behavior[AudioPlayerManagementCommand] =
        Behaviors.receiveMessagePartial {
          case AddPlaybackContextFactories(factories) =>
            factories foreach player.addPlaybackContextFactory
            Behaviors.same

          case RemovePlaybackContextFactories(factories) =>
            factories foreach player.removePlaybackContextFactory
            Behaviors.same

          case PublishToController(message) =>
            messageBus publish message
            Behaviors.same

          case close: Close =>
            player.removeEventSink(eventListener)
            messageBus removeListener messageBusRegistration
            messageBus publish UnregisterService(ControllerDependency)
            closePlayer(player, close)
        }

      def closePending(client: ActorRef[CloseAck]): Behavior[AudioPlayerManagementCommand] =
        Behaviors.receiveMessagePartial {
          case PlayerClosed(result) =>
            client ! CloseAck(result)
            Behaviors.stopped
        }

      def playerFailure(cause: Throwable): Behavior[AudioPlayerManagementCommand] =
        Behaviors.receiveMessagePartial {
          case Close(client, _) =>
            client ! CloseAck(Failure(cause))
            Behaviors.stopped
        }

      def closePlayer(player: AudioPlayer, close: Close): Behavior[AudioPlayerManagementCommand] = {
        implicit val timeout: Timeout = close.timeout
        player.close() map (_ => ()) onComplete { result =>
          context.self ! PlayerClosed(result)
        }
        closePending(close.client)
      }

      controllerCreationPending(Nil, Nil, None)
    }

  /**
    * Adds an event listener to the audio player associated with the given
    * controller that publishes all received events on the message bus.
    *
    * @param messageBus the message bus
    * @param controller the controller
    * @return the event listener ID
    */
  private def addEventListener(messageBus: MessageBus, controller: AudioPlayerController): Int = {
    val eventSink = Sink.foreach[Any](messageBus.publish)
    controller.player.registerEventSink(eventSink)
  }

  /**
    * Performs all necessary steps to register the given controller as a
    * service of the audio platform.
    *
    * @param messageBus the message bus
    * @param controller the controller
    * @return the message bus listener ID
    */
  private def registerController(messageBus: MessageBus, controller: AudioPlayerController): Int = {
    messageBus publish RegisterService(ControllerDependency)
    messageBus registerListener controller.receive
  }
}
