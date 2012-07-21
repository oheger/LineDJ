package de.oliver_heger.splaya.osgiutil

import org.scalatest.junit.JUnitSuite
import org.scalatest.mock.EasyMockSugar
import org.junit.Assert._
import org.junit.Before
import org.junit.Test

/**
 * Test class for ''ServiceWrapper''.
 */
class TestServiceWrapper extends JUnitSuite with EasyMockSugar {
  /** The wrapper to be tested. */
  private var wrapper: ServiceWrapper[Runnable] = _

  @Before def setUp() {
    wrapper = new ServiceWrapper
  }

  /**
   * Tests a newly created instance.
   */
  @Test def testInit() {
    assert(wrapper.get === null)
  }

  /**
   * Tests whether a service instance can be set.
   */
  @Test def testBind() {
    val svc = mock[Runnable]
    assertTrue("Wrong result", wrapper bind svc)
    assert(svc === wrapper.get)
  }

  /**
   * Tries to bind another service instance.
   */
  @Test def testBindDifferentSvc() {
    val svc = mock[Runnable]
    wrapper bind svc
    assertFalse("Wrong result", wrapper bind mock[Runnable])
    assert(svc === wrapper.get)
  }

  /**
   * Tests a successful unbind() operation.
   */
  @Test def testUnbindSuccess() {
    val svc = mock[Runnable]
    wrapper bind svc
    assertTrue("Wrong result", wrapper unbind svc)
    assertNull("Still got a service", wrapper.get)
  }

  /**
   * Tests unbind() if no service has been set.
   */
  @Test def testUnbindNoSvc() {
    assertFalse("Wrong result", wrapper unbind mock[Runnable])
  }

  /**
   * Tests unbind() if a different service instance is bound.
   */
  @Test def testUnbindDifferentSvc() {
    val svc = mock[Runnable]
    wrapper bind svc
    assertFalse("Wrong result", wrapper unbind mock[Runnable])
    assert(svc === wrapper.get)
  }

  /**
   * Tests whether the bound service instance can be cleared.
   */
  @Test def testClear() {
    wrapper bind mock[Runnable]
    wrapper clear()
    assertNull("Got a service", wrapper.get)
  }

  /**
   * Tests whether the wrapper can be converted to an Option.
   */
  @Test def testOptionConversion() {
    val svc = mock[Runnable]
    wrapper bind svc
    val opt: Option[Runnable] = wrapper
    assert(svc === opt.get)
  }

  /**
   * Tests a conversion to an Option if no service is bound.
   */
  @Test def testOptionConversionNoSvc() {
    val opt: Option[Runnable] = wrapper
    assert(opt === None)
  }
}
