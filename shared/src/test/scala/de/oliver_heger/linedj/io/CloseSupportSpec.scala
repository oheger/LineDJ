/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.io

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''CloseSupport''.
  */
class CloseSupportSpec(testSystem: ActorSystem) extends TestKit(testSystem)
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("CloseSupportSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates an object which can be tested.
    *
    * @return the test instance
    */
  private def createTestInstance(): CloseSupport =
    new CloseSupport {}

  /**
    * Creates a mock actor reference.
    *
    * @return the mock actor reference
    */
  private def mockActor(): ActorRef = TestProbe().ref

  /**
    * Verifies the correct creation of a close handler actor.
    *
    * @param subject        the subject actor
    * @param deps           the dependencies
    * @param factory        the actor factory
    * @param conditionState the condition state flag
    */
  private def verifyHandlerCreation(subject: ActorRef, deps: List[ActorRef], factory:
  ChildActorFactory, conditionState: Boolean): Unit = {
    verify(factory).createChildActor(Props(classOf[CloseHandlerActor], subject, deps,
      conditionState))
  }

  /**
    * Prepares a factory mock to return the specified actor reference for the
    * creation of the handler actor.
    *
    * @param handler the handler actor reference
    * @param factory the mock actor factory
    * @return ongoing stubbing
    */
  private def prepareHandlerActorCreation(handler: ActorRef, factory: ChildActorFactory):
  OngoingStubbing[ActorRef] =
    when(factory.createChildActor(any(classOf[Props]))).thenReturn(handler)

  "A CloseSupport" should "not have a request in progress initially" in {
    val support = createTestInstance()

    support.isCloseRequestInProgress shouldBe false
  }

  it should "indicate a request in progress after receiving one" in {
    val support = createTestInstance()

    support.onCloseRequest(mockActor(), List(mockActor()), mockActor(),
      mock[ChildActorFactory]) shouldBe true
    support.isCloseRequestInProgress shouldBe true
  }

  it should "create a correct close handler actor" in {
    val subject = mockActor()
    val deps = List(mockActor(), mockActor())
    val factory = mock[ChildActorFactory]
    val support = createTestInstance()

    support.onCloseRequest(subject, deps, mockActor(), factory)
    verifyHandlerCreation(subject, deps, factory, conditionState = true)
  }

  it should "take the condition state flag into account when creating the handler" in {
    val subject = mockActor()
    val deps = List(mockActor(), mockActor())
    val factory = mock[ChildActorFactory]
    val support = createTestInstance()

    support.onCloseRequest(subject, deps, mockActor(), factory, conditionState = false)
    verifyHandlerCreation(subject, deps, factory, conditionState = false)
  }

  it should "create a correct notifier actor" in {
    val subject = mockActor()
    val deps = List(mockActor(), mockActor())
    val target = mockActor()
    val handler = mockActor()
    val factory = mock[ChildActorFactory]
    prepareHandlerActorCreation(handler, factory)
    val support = createTestInstance()

    support.onCloseRequest(subject, deps, target, factory)
    verify(factory).createChildActor(Props(classOf[CloseNotifyActor], handler, subject,
      target))
  }

  it should "create only a single handler actor per close request" in {
    val subject = mockActor()
    val deps = List(mockActor(), mockActor())
    val factory = mock[ChildActorFactory]
    val support = createTestInstance()
    support.onCloseRequest(subject, deps, mockActor(), factory) shouldBe true

    support.onCloseRequest(subject, deps, mockActor(), factory) shouldBe false
    verifyHandlerCreation(subject, deps, factory, conditionState = true)
  }

  it should "reset the in-progress flag in the complete() method" in {
    val support = createTestInstance()
    support.onCloseRequest(mockActor(), List(mockActor()), mockActor(),
      mock[ChildActorFactory])

    support.onCloseComplete()
    support.isCloseRequestInProgress shouldBe false
  }

  it should "support notifications about a satisfied condition" in {
    val handler = TestProbe()
    val factory = mock[ChildActorFactory]
    prepareHandlerActorCreation(handler.ref, factory)
    val support = createTestInstance()
    support.onCloseRequest(mockActor(), List(mockActor()), mockActor(), factory,
      conditionState = false)

    support.onConditionSatisfied()
    handler.expectMsg(CloseHandlerActor.ConditionSatisfied)
  }

  it should "ignore a satisfied condition if no close operation is in progress" in {
    val support = createTestInstance()

    support.onConditionSatisfied()
  }
}
