package de.oliver_heger.splaya.engine

import scala.actors.Actor
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

import org.junit.Assert._

/**
 * A test implementation of an actor which stores all messages received in a
 * blocking queue. Clients can query this queue to retrieve the messages in a
 * thread-safe way. This class can be used by unit tests if messages are sent
 * by different threads.
 *
 * It is possible to pass in a partial function which is evaluated before the
 * message is stored in the internal queue. This makes it possible to handle
 * certain messages in a specific way while others are just stored as they are.
 * Note that all messages are stored, even if they could be handled by the
 * partial function.
 */
class QueuingActor(val messageHandler: PartialFunction[Any, Unit])
  extends Actor {
  /** The queue for storing messages. */
  val queue: BlockingQueue[Any] = new LinkedBlockingQueue

  /** The handler function. A default is used if not specified. */
  private val handler =
    if (messageHandler != null) messageHandler
    else new PartialFunction[Any, Unit] {
      def isDefinedAt(x: Any) = false

      def apply(msg: Any) {}
    }

  /**
   * Creates a new ''QueuingActor'' without a specific message handler function.
   */
  def this() = this(null)

  /**
   * Stores the message in the queue.
   */
  def act() {
    var running = true
    while (running) {
      receive {
        case Exit => running = false
        case msg =>
          if (handler.isDefinedAt(msg)) {
            handler(msg)
          }
          queue.put(msg)
      }
    }
  }

  /**
   * Returns the next message received by this actor. Waits for a certain amount
   * of time if necessary. If a timeout occurs, an assertion error is thrown.
   * @return the next message received by this actor
   */
  def nextMessage(): Any = {
    val msg = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
    assertNotNull("No message received!", msg)
    msg
  }

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
   * Causes this actor to stop.
   */
  def shutdown() {
    this ! Exit
  }
}
