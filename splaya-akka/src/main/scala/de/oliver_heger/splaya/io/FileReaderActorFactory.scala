package de.oliver_heger.splaya.io

import akka.actor.{ActorContext, ActorRef, Props}

/**
 * An internally used helper class for creating file reader actors.
 *
 * This class allows externalizing the creation of a [[FileReaderActor]]. This
 * simplifies testing because mock or stub actor references can be injected.
 */
private class FileReaderActorFactory {
  /**
   * Creates a new ''FileReaderActor'' using the specified context object.
   * @param context the ''ActorContext''
   * @return the newly created ''FileReaderActor''
   */
  def createFileReaderActor(context: ActorContext): ActorRef =
    context.actorOf(Props[FileReaderActor])
}
