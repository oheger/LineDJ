package de.oliver_heger.splaya.io

import akka.actor.ActorRef

/**
 * A message requesting an actor to close all resources it is currently using.
 *
 * This message is used abort currently running operations in a graceful way.
 * It is also sent to actors when the system goes down to implement a correct
 * shutdown process. Actors supporting this message should react by sending
 * a corresponding acknowledge message. This allows the system to find out
 * when all active actors have finished their activities and are ready to go
 * down.
 */
case object CloseRequest

/**
 * A message sent back by an actor to acknowledge that it has been closed.
 *
 * This message is typically sent as a reaction on a [[CloseRequest]] message.
 * The component initiation the close process can then be sure that the actor
 * finished its processing and can be shutdown safely.
 *
 * @param actor a reference to the actor that has been closed
 */
case class CloseAck(actor: ActorRef)
