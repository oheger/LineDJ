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

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.{actor => classic}

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
    * A trait to abstract over an invocation of an actor. The main purpose of
    * this trait is to handle both classic and typed actors in a homogenous
    * way. This is achieved by defining a generic ''send()'' function.
    */
  trait ActorInvocation {
    /**
      * Performs the actor invocation by sending the encapsulated message to
      * the target actor.
      */
    def send(): Unit
  }

  /**
    * A trait defining the invocation of a typed actor of a specific type. It
    * is used to model typesafe invocations for arbitrary actor types.
    * Instances can be created using factory functions from
    * [[ScheduledInvocationActor]].
    */
  trait TypedActorInvocation extends ActorInvocation {
    /** The type of the involved actor. */
    type MESSAGE

    /** The actor that is going to receive the message. */
    def receiver: ActorRef[MESSAGE]

    /** The message to be sent to the actor. */
    def message: MESSAGE

    override def send(): Unit = {
      receiver ! message
    }
  }

  /**
    * A data class representing an invocation of a classic actor.
    *
    * @param receiver the receiver actor
    * @param message  the message
    */
  case class ClassicActorInvocation(receiver: classic.ActorRef, message: Any) extends ActorInvocation {
    override def send(): Unit = {
      receiver ! message
    }
  }

  /**
    * The base trait for the commands handled by this actor implementation.
    */
  sealed trait ScheduledInvocationCommand

  /**
    * A command triggering the scheduled invocation of an actor.
    *
    * After the given delay, the invocation is performed.
    *
    * @param delay      the delay for the invocation
    * @param invocation the object describing the invocation
    */
  case class ActorInvocationCommand(delay: FiniteDuration,
                                    invocation: ActorInvocation) extends ScheduledInvocationCommand

  /**
    * A command causing the actor to stop itself. All pending delayed messages
    * are canceled.
    */
  case object Stop extends ScheduledInvocationCommand

  /**
    * An internal message this actor receives itself from the scheduler when
    * the delay is over. This is then used to do the delayed invocation.
    *
    * @param invocation the [[ActorInvocation]] to be performed
    */
  private case class InvokeAfterDelay(invocation: ActorInvocation) extends ScheduledInvocationCommand

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
    * Constructs an [[ActorInvocationCommand]] instance from the given
    * parameters.
    *
    * @param delay the delay for the invocation
    * @param to    the receiver actor
    * @param data  the message to be passed to the actor
    * @tparam M the type of the message
    * @return the resulting [[ActorInvocationCommand]]
    */
  def typedInvocationCommand[M](delay: FiniteDuration, to: ActorRef[M], data: M): ActorInvocationCommand =
    ActorInvocationCommand(delay, typedInvocation(to, data))

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
    case ActorInvocationCommand(delay, invocation) =>
      scheduler.startSingleTimer(InvokeAfterDelay(invocation), delay)
      Behaviors.same

    case InvokeAfterDelay(invocation) =>
      invocation.send()
      Behaviors.same

    case Stop =>
      scheduler.cancelAll()
      Behaviors.stopped
  }
}
