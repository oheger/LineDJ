package de.oliver_heger.splaya.engine.msg
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/**
 * A message that indicates that an actor should exit.
 *
 * It is also possible to wait until shutdown of the actor is complete. In order
 * for this to work, the number of actors to be shut down must be passed to the
 * constructor. Then one of the ''await()'' methods can be called.
 *
 * @param actorCount the number of actors to which this message is sent; this
 * is needed only if the ''await()'' methods are used to wait until shutdown
 * is complete; otherwise, 0 can be used (which is the default value)
 */
case class Exit(val actorCount: Int = 0) {
  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[Exit])

  /** A latch for waiting for the completion of a shutdown. */
  private val latch =
    if (actorCount > 0) Some(new CountDownLatch(actorCount))
    else None

  /**
   * Records that the specified object has processed the Exit message. This
   * implementation just prints a log message.
   * @param x the affected object
   */
  def confirmed(x: Any) {
    log.info("{} exited.", x)
    latch foreach (_.countDown())
  }

  /**
   * Waits until all actors to which this message was sent have confirmed their
   * shutdown. This method can only be used if the correct number of receiving
   * actors has been passed to the constructor.
   * @throws InterruptedException if waiting is interrupted
   */
  def await() {
    latch foreach (_.await())
  }

  /**
   * Waits until all actors to which this message was sent have confirmed their
   * shutdown or the specified timeout is reached. This method can only be
   * used if the correct number of receiving actors has been passed to the
   * constructor.
   * @param time the time to wait for the complete shutdown
   * @param unit the time unit
   * @return '''true''' if waiting for shutdown was successful; '''false''' if
   * the timeout was reached
   * @throws InterruptedException if waiting is interrupted
   */
  def await(time: Long, unit: TimeUnit): Boolean =
    latch forall (_.await(time, unit))
}
