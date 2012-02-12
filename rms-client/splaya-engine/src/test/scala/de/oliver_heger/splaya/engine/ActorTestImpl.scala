package de.oliver_heger.splaya.engine

import scala.actors.Actor
import scala.collection.mutable.ListBuffer

/**
 * A simple actor implementation which can be used for unit testing. This
 * actor stores all messages it receives in a list. Message receiving is
 * synchronous. Just call the {@code messageList()} method in order to retrieve
 * the list with the messages obtained so far.
 */
class ActorTestImpl extends Actor {
  /** A builder for generating the list with messages.*/
  private val msgListBuilder = ListBuffer[Any]()

  def act() {
    throw new UnsupportedOperationException("Unexpected method call");
  }

  /**
   * Sends a message to this actor. This implementation just stores the message.
   */
  override def !(msg: Any) {
    msgListBuilder += msg
  }

  def messageList: List[Any] = msgListBuilder.toList
}
