package de.oliver_heger.splaya.engine.msg

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertTrue
import de.oliver_heger.tsthlp.QueuingActor

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

  /**
   * Tests whether a message can be published to registered actors.
   */
  @Test def testPublish() {
    val actor1, actor2 = new QueuingActor
    actor1.start()
    actor2.start()
    Gateway.register(actor1)
    Gateway.register(actor2)
    val msg = 20120213214524L;
    Gateway.publish(msg)
    actor1.expectMessage(msg)
    actor2.expectMessage(msg)
    Gateway.unregister(actor1)
    Gateway.unregister(actor2)
  }

  /**
   * Tests whether an event listener can be removed.
   */
  @Test def testUnregister() {
    val actor = new QueuingActor
    actor.start()
    Gateway.register(actor)
    Gateway.unregister(actor)
    Gateway.publish("some message!")
    Thread.sleep(200)
    assertTrue("Got a message", actor.queue.isEmpty())
  }
}
