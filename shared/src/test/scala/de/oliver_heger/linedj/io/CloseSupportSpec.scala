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

package de.oliver_heger.linedj.io

import akka.actor.{ActorRef, Props}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.Mockito._
import org.mockito.Matchers.any
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

/**
  * Test class for ''CloseSupport''.
  */
class CloseSupportSpec extends FlatSpec with Matchers with MockitoSugar {
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
  private def mockActor(): ActorRef = mock[ActorRef]

  /**
    * Verifies the correct creation of a close handler actor.
    *
    * @param subject the subject actor
    * @param deps    the dependencies
    * @param factory the actor factory
    */
  private def verifyHandlerCreation(subject: ActorRef, deps: List[ActorRef], factory:
  ChildActorFactory): Unit = {
    verify(factory).createChildActor(Props(classOf[CloseHandlerActor], subject, deps))
  }

  "A CloseSupport" should "not have a request in progress initially" in {
    val support = createTestInstance()

    support.isCloseRequestInProgress shouldBe false
  }

  it should "indicate a request in progress after receiving one" in {
    val support = createTestInstance()

    support.onCloseRequest(mockActor(), List(mockActor()), mockActor(),
      mock[ChildActorFactory])
    support.isCloseRequestInProgress shouldBe true
  }

  it should "create a correct close handler actor" in {
    val subject = mockActor()
    val deps = List(mockActor(), mockActor())
    val factory = mock[ChildActorFactory]
    val support = createTestInstance()

    support.onCloseRequest(subject, deps, mockActor(), factory)
    verifyHandlerCreation(subject, deps, factory)
  }

  it should "create a correct notifier actor" in {
    val subject = mockActor()
    val deps = List(mockActor(), mockActor())
    val target = mockActor()
    val handler = mockActor()
    val factory = mock[ChildActorFactory]
    when(factory.createChildActor(any(classOf[Props]))).thenReturn(handler)
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
    support.onCloseRequest(subject, deps, mockActor(), factory)

    support.onCloseRequest(subject, deps, mockActor(), factory)
    verifyHandlerCreation(subject, deps, factory)
  }

  it should "reset the in-progress flag in the complete() method" in {
    val support = createTestInstance()
    support.onCloseRequest(mockActor(), List(mockActor()), mockActor(),
      mock[ChildActorFactory])

    support.onCloseComplete()
    support.isCloseRequestInProgress shouldBe false
  }
}
