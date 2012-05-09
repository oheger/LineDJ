package de.oliver_heger.splaya.engine.msg

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Test class for ''Exit''.
 */
class TestExit extends JUnitSuite {
  /**
   * Tests await() if the actor count is unknown.
   */
  @Test def testWaitNoActorCount() {
    val ex = Exit()
    ex.await()
  }

  /**
   * Tests await() with a timeout if the actor count is unknown.
   */
  @Test def testWaitTimeoutNoActorCount() {
    val ex = Exit()
    assert(true === ex.await(Long.MaxValue, TimeUnit.SECONDS))
  }

  /**
   * Tests await() if the number of actors is known.
   */
  @Test def testWaitActorCount() {
    val ex = Exit(2)
    ex.confirmed(1)
    ex.confirmed(2)
    ex.await()
  }

  /**
   * Tests await with a timeout if the actor count is known.
   */
  @Test def testWaitTimeoutActorCount() {
    val ex = Exit(2)
    ex.confirmed(1)
    assert(false === ex.await(100, TimeUnit.MILLISECONDS))
  }
}
