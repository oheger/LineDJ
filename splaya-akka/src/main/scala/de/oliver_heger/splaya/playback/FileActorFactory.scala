package de.oliver_heger.splaya.playback

import akka.actor.{ActorContext, ActorRef, Props}
import de.oliver_heger.splaya.io.{FileReaderActor, FileWriterActor}

/**
 * A class for creating instances of ''FileWriterActor'' or ''FileReaderActor''.
 *
 * This class is used internally by the actor managing local buffers. The actors
 * for writing into and reading from these buffers are created using this factory.
 * This allows for instance for injecting test implementations.
 */
private class FileActorFactory {
  /**
   * Creates a new ''ActorRef'' pointing a ''FileReaderActor''.
   * @param context the ''ActorContext'' for creating the actor
   * @return the reference to the newly created actor
   */
  def createFileReaderActor(context: ActorContext): ActorRef =
    context.actorOf(Props[FileReaderActor])

  /**
   * Creates a new ''ActorRef'' pointing to a ''FileWriterActor''.
   * @param context the ''ActorContext'' for creating the actor
   * @return the reference to the newly created actor
   */
  def createFileWriterActor(context: ActorContext): ActorRef =
    context.actorOf (Props[FileWriterActor])
}
