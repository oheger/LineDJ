/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.utils

import org.apache.pekko.actor.{Actor, ActorRef, Cancellable}

import scala.concurrent.duration.FiniteDuration

/**
 * A trait offering a generic method for scheduling a message to be sent
 * periodically to an actor.
 *
 * This trait can be used when testing actors that make use of the scheduler to
 * send messages periodically. This implementation is fully functional, it
 * delegates to the scheduler accessible from the actor context. Test classes
 * can inject a special mock implementation to verify that the scheduler was
 * correctly used.
 */
trait SchedulerSupport extends Actor {
  import context.dispatcher

  /**
    * Schedules a message for being sent periodically to a receiver. This
    * implementation delegates to the default scheduler of the system.
    *
    * @param initialDelay the initial delay
    * @param interval     the interval
    * @param receiver     the receiver
    * @param message      the message to be sent
    * @return an object for canceling task execution
    */
  def scheduleMessage(initialDelay: FiniteDuration, interval: FiniteDuration, receiver: ActorRef,
                      message: Any): Cancellable =
    context.system.scheduler.scheduleAtFixedRate(initialDelay, interval, receiver, message)

  /**
    * Schedules a message for being sent once to a receiver after a given delay.
    *
    * @param delay    the delay
    * @param receiver the receiver
    * @param message  the message to be sent
    * @return an object for canceling task execution
    */
  def scheduleMessageOnce(delay: FiniteDuration, receiver: ActorRef, message: Any): Cancellable =
    context.system.scheduler.scheduleOnce(delay, receiver, message)
}
