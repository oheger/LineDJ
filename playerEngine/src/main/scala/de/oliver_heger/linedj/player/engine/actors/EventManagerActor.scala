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
    * Command to stop this actor.
    */
  case class Stop[E]() extends EventManagerCommand[E]

  /**
    * Returns the behavior for a new instance that manages event listeners of
    * a specific type.
    *
    * @tparam E the event type supported by this actor
    * @return the behavior of the actor instance
    */
  def apply[E](): Behavior[EventManagerCommand[E]] = handleCommands(List.empty)

  /**
    * The actual implementation of the behavior managing the registered event
    * listeners as state.
    *
    * @param listeners the list with the registered listeners
    * @tparam E the event type supported by this actor
    * @return the behavior of this actor
    */
  private def handleCommands[E](listeners: List[ActorRef[E]]): Behavior[EventManagerCommand[E]] =
    Behaviors.receive[EventManagerCommand[E]] {
      case (ctx, RegisterListener(listener)) =>
        ctx.watch(listener)
        handleCommands(listener :: listeners)

      case (_, RemoveListener(listener)) =>
        handleCommands(listeners.filterNot(_ == listener))

      case (_, Publish(event)) =>
        listeners foreach (_ ! event)
        Behaviors.same

      case (ctx, Stop()) =>
        ctx.log.info("Stopping EventManagerActor {}.", ctx.self.path.name)
        Behaviors.stopped
    }.receiveSignal {
      case (context, Terminated(ref)) =>
        context.log.info(s"Removing terminated event listener ${ref.path.name}")
        handleCommands(listeners.filterNot(_ == ref))
    }
}
