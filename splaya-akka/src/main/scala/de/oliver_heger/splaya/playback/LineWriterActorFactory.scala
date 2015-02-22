package de.oliver_heger.splaya.playback

import akka.actor.{ActorContext, ActorRef, Props}

/**
 * An internal class for creating a [[LineWriterActor]] as a child of another
 * actor.
 *
 * This factory class is used by the main playback actor. By providing
 * alternative implementations, it is possible to inject special test line
 * writer actors.
 */
private class LineWriterActorFactory {
  /**
   * Creates a new line writer actor using the specified ''ActorContext''.
   * @param context the ''ActorContext''
   * @return the new line writer actor
   */
  def createLineWriterActor(context: ActorContext): ActorRef =
    context.actorOf(Props[LineWriterActor])
}
