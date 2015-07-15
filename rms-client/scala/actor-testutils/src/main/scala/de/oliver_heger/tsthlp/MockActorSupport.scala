package de.oliver_heger.tsthlp
import scala.actors.Actor
import org.junit.Assert.assertEquals

/**
 * A trait providing functionality for actor test implementations.
 *
 * When testing actors it is frequently required to check the messages sent to
 * an actor. This trait provides some basic functionality for querying and
 * checking messages. It can be mixed in by different mock actor
 * implementations.
 */
trait MockActorSupport extends Actor {
  /**
   * Returns the next message received by this test actor. If no such message
   * has been received, this method should fail. All other functionality for
   * checking messages is built on top of this method.
   * @return the next message received by this actor
   */
  def nextMessage(): Any

  /**
   * Convenience method for checking whether the expected message was received.
   * @param msg the expected message
   */
  def expectMessage(msg: Any) {
    assertEquals("Wrong message", msg, nextMessage())
  }

  /**
   * Skips the given number of messages. This method requires that at least
   * this number of messages has been received.
   * @param count the number of messages to skip
   */
  def skipMessages(count: Int) {
    for (i <- 1 to count) {
      nextMessage()
    }
  }

  /**
   * Ensures that no messages have been received by this actor. We send a dummy
   * message to ourselves and expect that this is the next message received.
   * @param skip the number of messages to skip before the queue is expected to
   * be empty
   */
  def ensureNoMessages(skip: Int = 0) {
    skipMessages(skip)
    val dummy = "DummyCheckEmptyMessage!"
    this ! dummy
    expectMessage(dummy)
  }

  /**
   * Returns a dummy handler function which does not react on any message.
   * @return the dummy handler function
   */
  val dummyHandler: PartialFunction[Any, Unit] =
    new PartialFunction[Any, Unit] {
      def isDefinedAt(x: Any) = false

      def apply(msg: Any) {}
    }
}
