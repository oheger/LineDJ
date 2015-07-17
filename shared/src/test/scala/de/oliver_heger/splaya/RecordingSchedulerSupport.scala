package de.oliver_heger.splaya

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorRef, Cancellable}
import de.oliver_heger.splaya.RecordingSchedulerSupport.{CancellableImpl, SchedulerInvocation}
import de.oliver_heger.splaya.utils.SchedulerSupport

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object RecordingSchedulerSupport {
  /** A default timeout when accessing the queue. */
  val DefaultQueueTimeout = 3.seconds

  /**
   * Tries to obtain a ''SchedulerInvocation'' object from the given queue. If
   * this is not possible in the given timeout, an ''AssertionError'' is
   * thrown.
   * @param queue the queue
   * @param timeout the timeout
   * @return the ''SchedulerInvocation'' obtained from the queue
   */
  def expectInvocation(queue: java.util.concurrent.BlockingQueue[SchedulerInvocation], timeout:
  FiniteDuration = DefaultQueueTimeout): SchedulerInvocation = {
    val result = queue.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
    if (result == null) {
      throw new AssertionError(s"Timeout ($timeout) when waiting for SchedulerInvocation!")
    }
    result
  }

  /**
   * A class combining the data of a scheduler invocation.
   * @param initialDelay the initial delay
   * @param interval the interval
   * @param receiver the receiver
   * @param message the message
   * @param cancellable the ''Cancellable'' returned to the caller
   */
  case class SchedulerInvocation(initialDelay: FiniteDuration, interval: FiniteDuration,
                                 receiver: ActorRef, message: Any, cancellable: Cancellable)

  /**
   * A straight-forward implementation of ''Cancellable'' which just stores the
   * cancel flag.
   */
  private class CancellableImpl extends Cancellable {
    private val cancelFlag = new AtomicBoolean

    override def cancel(): Boolean = {
      cancelFlag set true
      true
    }

    override def isCancelled: Boolean = cancelFlag.get
  }

}

/**
 * A specialized implementation of ''SchedulerSupport'' that can be used in
 * tests for actors.
 *
 * The idea of this trait is that invocations of the scheduler are only
 * recorded. To be more precise, the data passed to the scheduler is collected
 * and written into a queue. From there it can be fetched by a test class to
 * verify it.
 *
 * Concrete implementations have to provide the queue in which to store the
 * data.
 */
trait RecordingSchedulerSupport extends SchedulerSupport {
  /**
   * The queue in which to store data about invocations passed to the
   * scheduler.
   */
  val queue: java.util.concurrent.BlockingQueue[SchedulerInvocation]

  /**
   * @inheritdoc This implementation packs the passed in arguments into a
   *             ''SchedulerInvocation'' object and stores it in the managed
   *             queue
   */
  override def scheduleMessage(initialDelay: FiniteDuration, interval: FiniteDuration, receiver:
  ActorRef, message: Any)(implicit ec: ExecutionContext): Cancellable = {
    if (ec == null) {
      throw new AssertionError("No execution context!")
    }

    val cancellable = new CancellableImpl
    queue put SchedulerInvocation(initialDelay, interval, receiver, message, cancellable)
    cancellable
  }
}
