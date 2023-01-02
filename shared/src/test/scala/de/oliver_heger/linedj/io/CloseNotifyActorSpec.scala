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

package de.oliver_heger.linedj.io

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
  * Test class for ''CloseNotifyActor''.
  */
class CloseNotifyActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("CloseNotifyActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A CloseNotifyActor" should "send a CloseAck to the target actor" in {
    val helper = new CloseNotifyActorTestHelper

    helper.stopHandler().expectCloseAck()
  }

  it should "send a CloseAck only after the termination of the handler actor" in {
    val helper = new CloseNotifyActorTestHelper

    helper.expectNoCloseAck()
  }

  it should "stop itself after sending a CloseAck message" in {
    val helper = new CloseNotifyActorTestHelper

    helper.stopHandler().expectActorStopped()
  }

  /**
    * A test helper class managing dependencies of the actor under test.
    */
  private class CloseNotifyActorTestHelper {
    /** Probe for the handler actor. */
    private val probeHandler = TestProbe()

    /** Probe for the actor to be closed. */
    private val probeCloseActor = TestProbe()

    /** Probe for the target actor. */
    private val probeTarget = TestProbe()

    /** The actor to be tested. */
    private val actor = createTestActor()

    /**
      * Stops the handler actor.
      *
      * @return this test helper
      */
    def stopHandler(): CloseNotifyActorTestHelper = {
      system stop probeHandler.ref
      this
    }

    /**
      * Verifies that the target actor received a notification.
      *
      * @return this test helper
      */
    def expectCloseAck(): CloseNotifyActorTestHelper = {
      probeTarget.expectMsg(CloseAck(probeCloseActor.ref))
      this
    }

    /**
      * Verifies that no close notification has arrived so far.
      *
      * @return this test helper
      */
    def expectNoCloseAck(): CloseNotifyActorTestHelper = {
      probeTarget.expectNoMessage(500.millis)
      this
    }

    /**
      * Verifies that the test actor stopped itself after sending out the
      * CloseAck message.
      *
      * @return this test helper
      */
    def expectActorStopped(): CloseNotifyActorTestHelper = {
      val watcher = TestProbe()
      watcher watch actor
      watcher.expectMsgType[Terminated].actor should be(actor)
      this
    }

    /**
      * Creates the actor to be tested.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef =
      system.actorOf(Props(classOf[CloseNotifyActor], probeHandler.ref,
        probeCloseActor.ref, probeTarget.ref))
  }

}
