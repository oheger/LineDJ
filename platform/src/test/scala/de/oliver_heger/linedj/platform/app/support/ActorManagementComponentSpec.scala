/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.platform.app.support

import de.oliver_heger.linedj.platform.app.support.ActorManagementComponentSpec.ComponentTestImpl
import de.oliver_heger.linedj.platform.app.{ClientApplicationContextImpl, ClientContextSupport}
import org.apache.pekko.actor.{Actor, ActorRef, Props}
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicInteger

object ActorManagementComponentSpec:
  /**
    * A class implementing the trait under test together with some helper
    * traits.
    */
  private class ComponentTestImpl extends ClientContextSupport with SuperInvocationCheck
    with ActorManagementComponent:
    /** A counter for the invocations of the ''stopActors()'' function. */
    private val stopCounter = new AtomicInteger

    /**
      * Returns the number of invocations of the ''stopActors()'' function.
      *
      * @return the number of ''stopActors()'' calls
      */
    def numberOfStopActorsCalls: Int = stopCounter.get()

    override def stopActors(): Unit =
      stopCounter.incrementAndGet()

/**
  * Test class for [[ActorManagementComponent]].
  */
class ActorManagementComponentSpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "An ActorManagementComponent" should "call the original life-cycle methods" in:
    val helper = new ManagementComponentTestHelper

    helper.activateComponent().checkActivation()
      .deactivateComponent().checkDeactivation()

  it should "stop managed actors on deactivation" in:
    val helper = new ManagementComponentTestHelper

    helper.activateComponent()
      .deactivateComponent()
      .checkActorsStopped()

  it should "allow creating and registering actors" in:
    val helper = new ManagementComponentTestHelper
    val reference = mock[ActorRef]
    val name = "testActor"
    val props = Props(new Actor {
      override def receive: Receive = Actor.emptyBehavior
    })
    when(helper.clientContext.actorFactory.createActor(props, name)).thenReturn(reference)

    val actor = helper.component.createAndRegisterActor(props, name)
    actor should be(reference)
    helper.component getActor name should be(actor)

  /**
    * A helper class managing a test instance and its dependencies.
    */
  private class ManagementComponentTestHelper:
    /** A mock for the OSGi component context. */
    private val componentContext: ComponentContext = mock[ComponentContext]

    /** The application context. */
    val clientContext = new ClientApplicationContextImpl

    /** The instance to be tested. */
    val component: ComponentTestImpl = createTestInstance()

    /**
      * Calls ''activate()'' on the test component.
      *
      * @return this test helper
      */
    def activateComponent(): ManagementComponentTestHelper =
      component activate componentContext
      verifyNoInteractions(componentContext)
      this

    /**
      * Calls ''deactivate()'' on the test component.
      *
      * @return this test helper
      */
    def deactivateComponent(): ManagementComponentTestHelper =
      component deactivate componentContext
      verifyNoInteractions(componentContext)
      this

    /**
      * Checks whether activation logic was invoked on the test instance.
      *
      * @return this test helper
      */
    def checkActivation(): ManagementComponentTestHelper =
      component.activateCount should be(1)
      this

    /**
      * Checks whether deactivation logic was invoked on the test instance.
      *
      * @return this test helper
      */
    def checkDeactivation(): ManagementComponentTestHelper =
      component.deactivateCount should be(1)
      this

    /**
      * Checks whether the registered actors have been stopped exactly once.
      *
      * @return this test helper
      */
    def checkActorsStopped(): ManagementComponentTestHelper =
      component.numberOfStopActorsCalls should be(1)
      this

    /**
      * Creates a test instance.
      *
      * @return the test instance
      */
    private def createTestInstance(): ComponentTestImpl =
      val comp = new ComponentTestImpl
      comp initClientContext clientContext
      comp
