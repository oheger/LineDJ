package de.oliver_heger.splaya.tsthlp

import scala.actors.Actor
import scala.collection.mutable.ListBuffer
import org.junit.Assert.assertFalse

/**
 * A simple actor implementation which can be used for unit testing. This
 * actor stores all messages it receives in a list. Message receiving is
 * synchronous; so this mock implementation cannot be used if messages are sent
 * in a background thread. Just call the {@code messageList()} method in order
 * to retrieve the list with the messages obtained so far.
 *
 * It is possible to passe a partial function to the constructor which is
 * invoked on the messages received. That way behavior can be defined for
 * specific messages. If no handler function is provided, a default one is used
 * which does not react on messages.
 *
 * @param messageHandler the message handler function
 */
class ActorTestImpl(messageHandler: PartialFunction[Any, Unit] = null)
  extends Actor with MockActorSupport {
  /** The list with the messages received so far. */
  private var messages: Vector[Any] = Vector()

  /** The message handler function actually used by this actor. */
  private val handler =
    if (messageHandler != null) messageHandler
    else dummyHandler

  def act() {
    throw new UnsupportedOperationException("Unexpected method call");
  }

  /**
   * Sends a message to this actor. This implementation just stores the message.
   */
  override def !(msg: Any) {
    if (handler.isDefinedAt(msg)) {
      handler(msg)
    }
    messages = messages :+ msg
  }

  /**
   * Returns the list with the messages received so far.
   * @return the message list
   */
  def messageList: Seq[Any] = messages

  /**
   * Returns the next message received by this actor. It is extracted from the
   * message list.
   * @return the next message
   */
  def nextMessage(): Any = {
    assertFalse("No message received", messages.isEmpty)
    val result = messages.head
    messages = messages.tail
    result
  }
}
