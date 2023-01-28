/*
 * Copyright 2015-2023 The Developers Team.
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

import akka.{actor => classic}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}

import scala.concurrent.duration.FiniteDuration

/**
  * An actor implementation that can be used as (single-shot) scheduler.
  *
  * This actor allows sending an arbitrary message to an actor after a given
  * delay. This has a number of use cases for the player engine, but is useful
  * in a generic context as well, as a kind of rather simple scheduler.
  *
  * This implementation supports both invocations for typed and classic actors
  * as well.
  */
object ScheduledInvocationActor {
  /**
    * A trait defining the invocation of a typed actor of a specific type. It
    * is used to model typesafe invocations for arbitrary actor types.
    */
  trait TypedActorInvocation {
    /** The type of the involved actor. */
    type MESSAGE

    /** The actor that is going to receive the message. */
    def receiver: ActorRef[MESSAGE]

    /** The message to be sent to the actor. */
    def message: MESSAGE
  }

  /**
    * The base trait for the commands handled by this actor implementation.
    */
  sealed trait ScheduledInvocationCommand

  /**
    * A command triggering the scheduled invocation of a classic actor.
    *
    * @param delay    the delay for the invocation
    * @param receiver the receiving actor
    * @param message  the message to send
    */
  case class ClassicInvocationCommand(delay: FiniteDuration,
                                      receiver: classic.ActorRef,
                                      message: Any) extends ScheduledInvocationCommand

  /**
    * A command triggering the scheduled invocation of a typed actor.
    *
    * @param delay      the delay for the invocation
    * @param invocation the object describing the invocation
    */
  case class TypedInvocationCommand(delay: FiniteDuration,
                                    invocation: TypedActorInvocation) extends ScheduledInvocationCommand

  /**
    * A command causing the actor to stop itself. All pending delayed messages
    * are canceled.
    */
  case object Stop extends ScheduledInvocationCommand

  /**
    * An internal message this actor receives itself from the scheduler when
    * the delay is over. This is then used to do the delayed invocation.
    *
    * @param run a function that sends the message to the receiver
    */
  private case class InvokeAfterDelay(run: () => Unit) extends ScheduledInvocationCommand

  /**
    * Constructs a [[TypedActorInvocation]] instance for the given parameters.
    *
    * @param to   the receiver actor
    * @param data the message to be passed to the actor
    * @tparam M the type of the message
    * @return the resulting [[TypedActorInvocation]]
    */
  def typedInvocation[M](to: ActorRef[M], data: M): TypedActorInvocation =
    new TypedActorInvocation {
      override type MESSAGE = M

      override val receiver: ActorRef[MESSAGE] = to

      override val message: MESSAGE = data
    }

  /**
    * Constructs a [[TypedInvocationCommand]] instance from the given
    * parameters.
    *
    * @param delay the delay for the invocation
    * @param to    the receiver actor
    * @param data  the message to be passed to the actor
    * @tparam M the type of the message
    * @return the resulting [[TypedInvocationCommand]]
    */
  def typedInvocationCommand[M](delay: FiniteDuration, to: ActorRef[M], data: M): TypedInvocationCommand =
    TypedInvocationCommand(delay, typedInvocation(to, data))

  /**
    * Returns the behavior to create a new instance of this actor.
    *
    * @return the ''Behavior'' for a new actor instance
    */
  def apply(): Behavior[ScheduledInvocationCommand] = Behaviors.withTimers(handleMessages)

  /**
    * The main message handling function of this actor. It uses the provided
    * scheduler to implement the delay.
    *
    * @param scheduler the scheduler
    * @return the behavior of this actor
    */
  private[actors] def handleMessages(scheduler: TimerScheduler[ScheduledInvocationCommand]):
  Behavior[ScheduledInvocationCommand] = Behaviors.receiveMessage {
    case TypedInvocationCommand(delay, invocation) =>
      scheduler.startSingleTimer(InvokeAfterDelay { () => invocation.receiver ! invocation.message }, delay)
      Behaviors.same

    case ClassicInvocationCommand(delay, receiver, message) =>
      scheduler.startSingleTimer(InvokeAfterDelay { () => receiver ! message }, delay)
      Behaviors.same

    case InvokeAfterDelay(run) =>
      run()
      Behaviors.same

    case Stop =>
      scheduler.cancelAll()
      Behaviors.stopped
  }
}
