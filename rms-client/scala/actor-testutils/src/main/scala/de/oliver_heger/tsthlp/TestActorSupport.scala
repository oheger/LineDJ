package de.oliver_heger.tsthlp

import scala.actors.Actor

import org.junit.Assert.fail
import org.junit.After

/**
 * A trait with some functionality related to testing of actors.
 *
 * This trait especially provides functionality for gracefully shutting down a
 * test actor and for waiting until it is actually down. This is often needed
 * to safely test the interaction of the test actor with other components.
 */
trait TestActorSupport {
  /** The concrete actor type to be tested. */
  type ActorUnderTest <: Actor

  /** The test actor. */
  protected var actor: ActorUnderTest

  /**
   * Performs cleanup at the end of a test. If the test actor has not been
   * terminated yet, this is done now.
   */
  @After def tearDown() {
    if (actor != null) {
      actor ! new WaitForExit
    }
  }

  /**
   * Terminates the given actor and waits until it is down.
   * @param target the actor to shut down
   * @param timeout the time (in milliseconds) to wait for the actor's shutdown
   */
  def closeActor(target: Actor, timeout: Long = ActorTrigger.DefaultTimeout) {
    val ex = new WaitForExit
    if (!ex.shutdownActor(target, timeout)) {
      fail("Actor did not exit!")
    }
  }

  /**
   * Terminates the test actor and waits until it is down.
   * @param timeout the time (in milliseconds) to wait for the actor's shutdown
   */
  def shutdownActor(timeout: Long = ActorTrigger.DefaultTimeout) {
    closeActor(actor, timeout)
    actor = null.asInstanceOf[ActorUnderTest]
  }
}
