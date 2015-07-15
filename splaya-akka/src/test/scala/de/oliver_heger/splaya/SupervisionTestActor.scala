package de.oliver_heger.splaya

import akka.actor._
import akka.testkit.TestActorRef

/**
 * An actor implementation that simplifies testing error handling and supervision.
 *
 * This actor creates a child actor defined by a passed in ''Props'' object.
 * It also sets the specified supervision strategy. The child actor is
 * made available via a public field (and can be accessed via a test actor
 * reference. Test class can send arbitrary messages to the test actor
 * reference and check whether it reacts accordingly. The basic idea is that
 * messages are sent to the test actor which causes it to throw an exception
 * and die. A test probe can be used to watch the child actor and receive the
 * termination message.
 *
 * @param supervisorStrategy the ''SupervisorStrategy'' to be used
 * @param childProps the properties for creating the child actor
 */
class SupervisionTestActor(override val supervisorStrategy: SupervisorStrategy, childProps:
Props) extends Actor {
  /** The child actor created by this actor. */
  var childActor: ActorRef = _

  override def receive: Receive = Actor.emptyBehavior

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    childActor = context.actorOf(childProps)
  }
}

object SupervisionTestActor {
  /**
   * Creates a test reference of type ''SupervisionTestActor'' with the specified parameters.
   * @param system the actor system
   * @param supervisorStrategy the ''SupervisorStrategy''
   * @param childProps the properties for the child actor
   * @return the test reference
   */
  def apply(system: ActorSystem, supervisorStrategy: SupervisorStrategy, childProps: Props):
  TestActorRef[SupervisionTestActor] =
    TestActorRef[SupervisionTestActor](Props(classOf[SupervisionTestActor], supervisorStrategy,
      childProps))(system)
}
