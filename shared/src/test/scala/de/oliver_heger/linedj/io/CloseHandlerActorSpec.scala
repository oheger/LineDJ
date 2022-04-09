/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.{ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

/**
  * Test class for ''CloseHandlerActor''.
  */
class CloseHandlerActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("CloseHandlerActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A CloseHandlerActor" should "send close actors a CloseRequest" in {
    val helper = new CloseHandlerActorTestHelper

    helper.expectCloseRequests()
  }

  it should "send a close ack when all monitored actors are closed" in {
    val helper = new CloseHandlerActorTestHelper

    helper.sendCloseAcks().expectCloseComplete()
  }

  it should "not send a close ack before monitored actors are closed" in {
    val helper = new CloseHandlerActorTestHelper

    helper.sendCloseAcks(to = 1).expectNoCloseComplete()
      .sendCloseAcks(from = 1).expectCloseComplete()
  }

  it should "stop itself after monitored actors are closed" in {
    val helper = new CloseHandlerActorTestHelper

    helper.sendCloseAcks().expectActorStopped()
  }

  it should "death watch actors to be closed" in {
    val helper = new CloseHandlerActorTestHelper

    helper.expectCloseRequests().stopMonitoredActor(0).sendCloseAcks(from = 1)
      .expectCloseComplete()
  }

  it should "not complete the close operation before the condition is satisfied" in {
    val helper = new CloseHandlerActorTestHelper(initialCondition = false)

    helper.sendCloseAcks().expectNoCloseComplete()
      .conditionSatisfied().expectCloseComplete()
  }

  /**
    * A test helper class managing dependencies for the actor to be tested.
    *
    * @param initialCondition the condition flag to be passed to the test actor
    */
  private class CloseHandlerActorTestHelper(initialCondition: Boolean = true) {
    /** The number of actors to be closed. */
    val CloseCount = 2

    /** Test probe for the source actor. */
    private val probeSource = TestProbe()

    /** Array with probes for actors to be closed. */
    private val closeActors = createCloseActors()

    /** The actor to be tested. */
    private val handler = createTestActor()

    /**
      * Expects that all actors to close have received a close request.
      *
      * @return this test helper
      */
    def expectCloseRequests(): CloseHandlerActorTestHelper = {
      closeActors foreach { p =>
        p.expectMsg(CloseRequest)
      }
      this
    }

    /**
      * Sends close ack messages from the specified close actors.
      *
      * @param from the from index
      * @param to   the to index (excluding)
      * @return this test helper
      */
    def sendCloseAcks(from: Int = 0, to: Int = CloseCount): CloseHandlerActorTestHelper = {
      closeActors.slice(from, to).map(p => CloseAck(p.ref)).foreach(handler.receive)
      this
    }

    /**
      * Expects that the source actor has been sent a CloseComplete message.
      *
      * @return this test helper
      */
    def expectCloseComplete(): CloseHandlerActorTestHelper = {
      probeSource.expectMsg(CloseHandlerActor.CloseComplete)
      this
    }

    /**
      * Verifies that no CloseComplete message was sent to the source actor.
      *
      * @return this test helper
      */
    def expectNoCloseComplete(): CloseHandlerActorTestHelper = {
      val Ping = "Ping"
      probeSource.ref ! Ping
      probeSource.expectMsg(Ping)
      this
    }

    /**
      * Verifies that the test actor has terminated.
      *
      * @return this test helper
      */
    def expectActorStopped(): CloseHandlerActorTestHelper = {
      val watcher = TestProbe()
      watcher watch handler
      watcher.expectMsgType[Terminated].actor should be(handler)
      this
    }

    /**
      * Stops one of the actors that are to be closed.
      *
      * @param idx the index of the actor to stop
      * @return this test helper
      */
    def stopMonitoredActor(idx: Int): CloseHandlerActorTestHelper = {
      system stop closeActors(idx).ref
      this
    }

    /**
      * Sends a message to the test actor that the condition is satisfied.
      *
      * @return this test helper
      */
    def conditionSatisfied(): CloseHandlerActorTestHelper = {
      handler receive CloseHandlerActor.ConditionSatisfied
      this
    }

    /**
      * Creates an array with the actors to be closed.
      *
      * @return the array with test probes for close actors
      */
    private def createCloseActors(): Array[TestProbe] =
      (1 to CloseCount).map(_ => TestProbe()).toArray

    /**
      * Creates the test actor.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[CloseHandlerActor] =
      TestActorRef(Props(classOf[CloseHandlerActor], probeSource.ref,
        closeActors.map(_.ref).toSeq, initialCondition))
  }

}
