/*
 * Copyright 2015-2022 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Terminated}

/**
  * An actor implementation responsible for the management of event listeners
  * and publishing of events to these listeners.
  *
  * This actor is used to send information about state changes of the player
  * engine to interested parties. Its protocol allows the registration and
  * de-registration of event listeners, and the publishing of events of a
  * specific type. Event listeners are themselves typed actors; so sending an
  * event to a listeners means passing a message to an actor.
  *
  * This actor watches the registered event listener actors; in case a listener
  * dies, it is automatically removed from the managed list of listeners.
  */
object EventManagerActor {
  /**
    * The base trait for the commands processed by this actor. All commands are
    * typed by the events that are to be processed.
    *
    * @tparam E the event type supported by the actor
    */
  sealed trait EventManagerCommand[E]

  /**
    * Command to register an event listener actor at this actor.
    *
    * @param listener the listener to register
    * @tparam E the event type supported by the actor
    */
  case class RegisterListener[E](listener: ActorRef[E]) extends EventManagerCommand[E]

  /**
    * Command to remove a specific listener from this actor.
    *
    * @param listener the listener to remove
    * @tparam E the event type supported by the actor
    */
  case class RemoveListener[E](listener: ActorRef[E]) extends EventManagerCommand[E]

  /**
    * Command to publish an event to all registered listeners.
    *
    * @param event the event to publish
    * @tparam E the event type supported by the actor
    */
  case class Publish[E](event: E) extends EventManagerCommand[E]

  /**
    * Command to request a reference to a publisher actor.
    *
    * Although it is possible to publish events via the [[EventManagerActor]]
    * protocol, it is not always desirable to pass around a reference to this
    * actor type to clients that just need to publish events. This is due to
    * the fact that the protocol allows further actions to manipulate the
    * managed event listeners and even to stop the actor. Therefore, via this
    * command, a dedicated actor can be requested that only supports publishing
    * events of the target type to the registered listeners and nothing more.
    * The lifecycle of this publisher actor is tight to the owning
    * [[EventManagerActor]].
    *
    * @param client the client requesting the publisher actor
    * @tparam E the event type supported by the actor
    */
  case class GetPublisher[E](client: ActorRef[PublisherReference[E]]) extends EventManagerCommand[E]

  /**
    * Command to stop this actor.
    */
  case class Stop[E]() extends EventManagerCommand[E]

  /**
    * A data class for the message sent in response on a [[GetPublisher]]
    * request. The contained actor reference can be used to publish events of
    * the supported type.
    *
    * @param publisher the actor to publish events
    * @tparam E the event type supported by the actor
    */
  case class PublisherReference[E](publisher: ActorRef[Publish[E]])

  /**
    * Returns the behavior for a new instance that manages event listeners of
    * a specific type.
    *
    * @tparam E the event type supported by this actor
    * @return the behavior of the actor instance
    */
  def apply[E](): Behavior[EventManagerCommand[E]] = Behaviors.setup[EventManagerCommand[E]] { context =>
    val publisher: ActorRef[Publish[E]] = context.messageAdapter(event => event)

    def handleCommands(listeners: List[ActorRef[E]]): Behavior[EventManagerCommand[E]] =
      Behaviors.receiveMessage[EventManagerCommand[E]] {
        case RegisterListener(listener) =>
          context.watch(listener)
          handleCommands(listener :: listeners)

        case RemoveListener(listener) =>
          handleCommands(listeners.filterNot(_ == listener))

        case Publish(event) =>
          listeners foreach (_ ! event)
          Behaviors.same

        case GetPublisher(client) =>
          client ! PublisherReference(publisher)
          Behaviors.same

        case Stop() =>
          context.log.info("Stopping EventManagerActor {}.", context.self.path.name)
          Behaviors.stopped
      }.receiveSignal {
        case (context, Terminated(ref)) =>
          context.log.info(s"Removing terminated event listener ${ref.path.name}")
          handleCommands(listeners.filterNot(_ == ref))
      }

    handleCommands(List.empty)
  }
}
