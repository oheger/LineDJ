package de.oliver_heger.tsthlp
import java.util.concurrent.CountDownLatch
import scala.actors.Actor
import java.util.concurrent.TimeUnit
import java.io.Closeable

/**
 * A specialized implementation of an exit command (represented as a
 * ''Closeable'') which can be used for tests with actors. The class provides
 * functionality for waiting until an actor has processed the Exit message.
 */
class WaitForExit extends Closeable {
  /** Constant for the default timeout when waiting for an actor to exit. */
  val DefaultTimeout = 5000

  /** The latch for waiting. */
  private val latch = new CountDownLatch(1)

  /**
   * Notifies this object that an actor has processed the close command. This
   * implementation ensures that waiting for the exit of the actor can now be
   * terminated.
   */
  override def close() {
    latch.countDown()
  }

  /**
   * Causes the specified actor to exit by sending this message to it. Then this
   * method waits until it has been processed.
   * @param act the actor
   * @param timeout the timeout to wait for (in milliseconds)
   * @return a flag whether the actor exited in the specified timeout
   */
  def shutdownActor(act: Actor, timeout: Long): Boolean = {
    act ! this
    latch.await(timeout, TimeUnit.MILLISECONDS)
  }

  /**
   * Causes the specified actor to exit using a default timeout.
   * @param act the actor
   * @return a flag whether the actor exited in the default timeout
   */
  def shutdownActor(act: Actor): Boolean =
    shutdownActor(act, DefaultTimeout)
}
