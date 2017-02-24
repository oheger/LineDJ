/*
 * Copyright 2015-2017 The Developers Team.
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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.io.FileReaderActor
import de.oliver_heger.linedj.platform.app.{ClientApplicationContextImpl, ClientContextSupport}
import org.mockito.Mockito._
import org.osgi.service.component.ComponentContext
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

object ActorManagementSpec {
  /** Prefix for an actor name. */
  private val ActorName = "testActor_"

  /**
    * Generates an actor name based on the given index.
    *
    * @param idx the index
    * @return the actor name
    */
  private def genActorName(idx: Int): String = ActorName + idx

  private class ComponentTestImpl extends ClientContextSupport with SuperInvocationCheck
    with ActorManagement {
    /**
      * Overridden to increase visibility.
      */
    override def stopActors(): Unit = super.stopActors()
  }
}

/**
  * Test class for ''ActorManagement''.
  */
class ActorManagementSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {

  import ActorManagementSpec._

  def this() = this(ActorSystem("ActorManagementSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "An ActorManagement" should "call the original life-cycle methods" in {
    val helper = new ActorManagementTestHelper

    helper.activateComponent().checkActivation()
      .deactivateComponent().checkDeactivation()
  }

  it should "make registered actors accessible" in {
    val helper = new ActorManagementTestHelper

    val (name1, actor1) = helper.registerActor()
    val (name2, actor2) = helper.registerActor()
    helper.component.getActor(name1) should be(actor1)
    helper.component.getActor(name2) should be(actor2)
  }

  it should "throw an exception when querying an unknown actor" in {
    val helper = new ActorManagementTestHelper

    intercept[NoSuchElementException] {
      helper.component getActor genActorName(1)
    }
  }

  it should "stop registered actors" in {
    val helper = new ActorManagementTestHelper
    val (_, actor1) = helper.registerActor()
    val (_, actor2) = helper.registerActor()

    helper.deactivateComponent()
      .checkActorsStopped(actor1, actor2)
  }

  it should "allow creating and registering actors" in {
    val helper = new ActorManagementTestHelper
    val probe = TestProbe()
    val name = genActorName(42)
    val props = Props[FileReaderActor]
    when(helper.clientContext.actorFactory.createActor(props, name)).thenReturn(probe.ref)

    val actor = helper.component.createAndRegisterActor(props, name)
    actor should be(probe.ref)
    helper.component getActor name should be(actor)
  }

  it should "allow stopping managed actors directly" in {
    val helper = new ActorManagementTestHelper
    val (name, actor) = helper.registerActor()

    helper.component.stopActors()
    helper.checkActorsStopped(actor)
    intercept[NoSuchElementException] {
      helper.component getActor name
    }
  }

  /**
    * A helper class managing a test instance and its dependencies.
    */
  private class ActorManagementTestHelper {
    /** The application context. */
    val clientContext = new ClientApplicationContextImpl

    /** A mock for the OSGi component context. */
    val componentContext: ComponentContext = mock[ComponentContext]

    /** The instance to be tested. */
    val component: ComponentTestImpl = createTestInstance()

    /** A counter for generating actor names. */
    private var actorCount = 0

    /**
      * Calls ''activate()'' on the test component.
      *
      * @return this test helper
      */
    def activateComponent(): ActorManagementTestHelper = {
      component activate componentContext
      verifyZeroInteractions(componentContext)
      this
    }

    /**
      * Calls ''deactivate()'' on the test component.
      *
      * @return this test helper
      */
    def deactivateComponent(): ActorManagementTestHelper = {
      component deactivate componentContext
      verifyZeroInteractions(componentContext)
      this
    }

    /**
      * Checks whether activation logic was invoked on the test instance.
      *
      * @return this test helper
      */
    def checkActivation(): ActorManagementTestHelper = {
      component.activateCount should be(1)
      this
    }

    /**
      * Checks whether deactivation logic was invoked on the test instance.
      *
      * @return this test helper
      */
    def checkDeactivation(): ActorManagementTestHelper = {
      component.deactivateCount should be(1)
      this
    }

    /**
      * Creates a mock actor and registers it at the test component.
      *
      * @return a pair of the actor name and the actor reference
      */
    def registerActor(): (String, ActorRef) = {
      actorCount += 1
      val name = genActorName(actorCount)
      val actor = TestProbe().ref
      component.registerActor(name, actor)
      (name, actor)
    }

    /**
      * Checks whether all of the specified actors have been stopped.
      *
      * @param refs the expected actor references
      * @return this test helper
      */
    def checkActorsStopped(refs: ActorRef*): ActorManagementTestHelper = {
      refs foreach (r => verify(clientContext.actorSystem).stop(r))
      this
    }

    /**
      * Creates a test instance.
      *
      * @return the test instance
      */
    private def createTestInstance(): ComponentTestImpl = {
      val comp = new ComponentTestImpl
      comp initClientContext clientContext
      comp
    }
  }

}
