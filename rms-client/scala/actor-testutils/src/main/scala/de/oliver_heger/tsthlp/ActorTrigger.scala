package de.oliver_heger.tsthlp

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

/**
 * A helper class which can be used to synchronize with the processing of an
 * actor.
 *
 * Actors in this project use the ''Closeable'' interface to notify their
 * termination or other important points in their life cycle. This class
 * provides an implementation of this interface allowing a client to wait
 * until the ''close()'' method is invoked.
 */
class ActorTrigger extends Closeable {
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
   * Waits for this trigger until the given timeout is reached or it is fired.
   * @param timeout the timeout for the wait (in milliseconds)
   * @return a flag whether the trigger was fired
   */
  def await(timeout: Long = ActorTrigger.DefaultTimeout): Boolean =
    latch.await(timeout, TimeUnit.MILLISECONDS)

  /**
   * Waits for this trigger or fails if the timeout is reached.
   * @param timeout the timeout for the wait (in milliseconds)
   */
  def awaitOrFail(timeout: Long = ActorTrigger.DefaultTimeout) {
    if (!await(timeout)) {
      fail("Wait for trigger reached timeout!")
    }
  }
}

/**
 * The companion object.
 */
object ActorTrigger {
  /** Constant for the default timeout when waiting for actor trigger. */
  val DefaultTimeout = 5000L
}
