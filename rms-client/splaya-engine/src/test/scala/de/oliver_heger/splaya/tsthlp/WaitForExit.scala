package de.oliver_heger.splaya.tsthlp
import java.util.concurrent.CountDownLatch
import scala.actors.Actor
import java.util.concurrent.TimeUnit
import de.oliver_heger.splaya.engine.msg.Exit

/**
 * A specialized [[de.oliver_heger.splaya.engine.Exit]] implementation which can
 * be used for tests with actors. The class provides additional functionality
 * for waiting until an actor has processed the Exit message.
 */
class WaitForExit extends Exit(1) {
  /** Constant for the default timeout when waiting for an actor to exit. */
  val DefaultTimeout = 5000

  /**
   * Causes the specified actor to exit by sending this message to it. Then this
   * method waits until it has been processed.
   * @param act the actor
   * @param timeout the timeout to wait for (in milliseconds)
   * @return a flag whether the actor exited in the specified timeout
   */
  def shutdownActor(act: Actor, timeout: Long): Boolean = {
    act ! this
    await(timeout, TimeUnit.MILLISECONDS)
  }

  /**
   * Causes the specified actor to exit using a default timeout.
   * @param act the actor
   * @return a flag whether the actor exited in the default timeout
   */
  def shutdownActor(act: Actor): Boolean =
    shutdownActor(act, DefaultTimeout)
}
