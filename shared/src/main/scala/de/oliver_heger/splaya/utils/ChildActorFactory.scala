package de.oliver_heger.splaya.utils

import akka.actor.{Actor, ActorRef, Props}

/**
 * A trait offering a generic method for creating child actors.
 *
 * When testing actors that dynamically create child actors there is often the
 * need to inject specific mock actor references. This trait provides a
 * solution for this problem. It defines a generic method for creating actors
 * (using the same ''Props'' mechanism as regular actor creation). Test classes
 * can override this method to return arbitrary test actor references.
 *
 * This trait is fully functional; it can be directly mixed into actor classes
 * that need to create child actors.
 */
trait ChildActorFactory extends Actor {
  /**
   * Creates a child actor based on the specified ''Props'' object. This
   * implementation uses the actor's context to actually create the child.
   * @param p the ''Props'' defining the actor to be created
   * @return the ''ActorRef'' to the new child actor
   */
  def createChildActor(p: Props): ActorRef = context.actorOf(p)
}
