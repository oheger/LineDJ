package de.oliver_heger.test.actors

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.junit.Before
import org.junit.After

/**
 * Test class for Gateway.
 */
class TestGateway extends JUnitSuite {
  /** Constant for the name of the test actor.*/
  private val TestActor = "MyTestActor"

  @Before def setUp() {
    Gateway.start()
  }

  /**
   * Tests whether the gateway can delegate messages to another actor.
   */
  @Test def testDelegateToActor() {
    val actor = new QueuingActor
    actor.start()
    Gateway += TestActor -> actor
    val msg = "A test message"
    Gateway ! TestActor -> msg
    actor.expectMessage(msg)
  }
}
