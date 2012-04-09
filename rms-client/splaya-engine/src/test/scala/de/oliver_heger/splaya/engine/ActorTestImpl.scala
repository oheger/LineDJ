package de.oliver_heger.splaya.engine

import scala.actors.Actor
import scala.collection.mutable.ListBuffer
import org.junit.Assert.assertFalse

/**
 * A simple actor implementation which can be used for unit testing. This
 * actor stores all messages it receives in a list. Message receiving is
 * synchronous; so this mock implementation cannot be used if messages are sent
 * in a background thread. Just call the {@code messageList()} method in order
 * to retrieve the list with the messages obtained so far.
 */
class ActorTestImpl extends Actor with MockActorSupport {
  /** The list with the messages received so far. */
  private var messages: Vector[Any] = Vector()

  def act() {
    throw new UnsupportedOperationException("Unexpected method call");
  }

  /**
   * Sends a message to this actor. This implementation just stores the message.
   */
  override def !(msg: Any) {
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
