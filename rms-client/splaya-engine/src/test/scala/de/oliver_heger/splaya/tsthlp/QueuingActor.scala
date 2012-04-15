package de.oliver_heger.splaya.tsthlp

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
 *
 * @param messageHandler the message handler function
 */
class QueuingActor(val messageHandler: PartialFunction[Any, Unit] = null)
  extends Actor with MockActorSupport {
  /** An internally used exit message. */
  private val ExitMessage = "QueuingActor.ExitMessage"

  /** The timeout used by this actor (in seconds). */
  private val TimeOut = 5

  /** The queue for storing messages. */
  val queue: BlockingQueue[Any] = new LinkedBlockingQueue

  /** The handler function. A default is used if not specified. */
  private val handler =
    if (messageHandler != null) messageHandler
    else dummyHandler

  /**
   * Stores the message in the queue.
   */
  def act() {
    var running = true
    while (running) {
      receive {
        case ExitMessage => running = false
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
    val msg = queue.poll(TimeOut, java.util.concurrent.TimeUnit.SECONDS)
    assertNotNull("No message received!", msg)
    msg
  }

  /**
   * Causes this actor to stop.
   */
  def shutdown() {
    this ! ExitMessage
  }
}
