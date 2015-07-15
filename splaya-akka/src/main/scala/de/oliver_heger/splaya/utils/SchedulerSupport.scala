package de.oliver_heger.splaya.utils

import akka.actor.{Actor, ActorRef, Cancellable}

import scala.concurrent.ExecutionContext
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
  /**
   * Schedules a message for being sent periodically to a receiver. This
   * implementation delegates to the default scheduler of the system.
   * @param initialDelay the initial delay
   * @param interval the interval
   * @param receiver the receiver
   * @param message the message to be sent
   * @param ec an implicit execution context
   * @return an option for canceling task execution
   */
  def scheduleMessage(initialDelay: FiniteDuration, interval: FiniteDuration, receiver: ActorRef,
                      message: Any)
                     (implicit ec: ExecutionContext): Cancellable =
    context.system.scheduler.schedule(initialDelay, interval, receiver, message)
}
