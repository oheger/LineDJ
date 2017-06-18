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

package de.oliver_heger.linedj.io.stream

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.SupervisionTestActor
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

/**
  * Test class for ''StreamSourceActorWrapper''.
  */
class StreamSourceActorWrapperSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with FlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("StreamSourceActorWrapperSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A StreamSourceActorWrapper" should "communicate with the wrapped actor" in {
    val Request = "Ping"
    val Response = "Pong"
    val helper = new ActorWrapperTestHelper

    helper.send(Request)
      .expectForward(Request)
      .answer(Response)
      .expectAnswer(Response)
  }

  it should "ignore an unexpected message from the wrapped actor" in {
    val Msg = "Foo"
    val helper = new ActorWrapperTestHelper

    helper.answer(Msg)
      .send(Msg)
      .expectForward(Msg)
  }

  it should "reset the client after receiving an answer" in {
    val Msg1 = "Message1"
    val Msg2 = "Message2"
    val helper = new ActorWrapperTestHelper

    helper.send(Msg1)
      .expectForward(Msg1)
      .answer(Msg1)
      .expectAnswer(Msg1)
      .answer(Msg1)
      .send(Msg2)
      .expectForward(Msg2)
      .answer(Msg2)
      .expectAnswer(Msg2)
  }

  it should "ignore another request while one is ongoing" in {
    val Req1 = "Request1"
    val Req2 = "Request2"
    val Response = "42"
    val helper = new ActorWrapperTestHelper

    helper.send(Req1)
      .send(Req1)
      .expectForward(Req1)
      .answer(Response)
      .send(Req2)
      .expectForward(Req2)
      .expectAnswer(Response)
  }

  it should "handle a failed request" in {
    val helper = new ActorWrapperTestHelper

    helper.send("some request")
      .stopWrappedActor()
      .expectAnswer(WrappedActorTerminated)
  }

  it should "detect a terminated actor outside of request processing" in {
    val helper = new ActorWrapperTestHelper

    helper.stopWrappedActor()
      .waitForWrappedActorTermination()
      .send("some request")
      .expectAnswer(WrappedActorTerminated)
  }

  it should "only answer with the terminated message after the wrapped actor died" in {
    val helper = new ActorWrapperTestHelper

    helper.send("some request")
      .stopWrappedActor()
      .waitForWrappedActorTermination()
      .send("another request")
      .expectAnswer(WrappedActorTerminated)
      .expectAnswer(WrappedActorTerminated)
  }

  it should "stop the wrapped actor when it is stopped" in {
    val helper = new ActorWrapperTestHelper

    helper.stopTestActor()
      .waitForWrappedActorTermination()
  }

  /**
    * A test helper class managing dependencies of a test instance.
    */
  private class ActorWrapperTestHelper {
    /** Test probe for the wrapped actor. */
    private val probeWrapped = TestProbe()

    /** The actor to be tested. */
    private val actor = createTestActor()

    /**
      * Sends the specified message to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): ActorWrapperTestHelper = {
      actor ! msg
      this
    }

    /**
      * Expects that the specified message has been forwarded to the wrapped
      * actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectForward(msg: Any): ActorWrapperTestHelper = {
      probeWrapped.expectMsg(msg)
      this
    }

    /**
      * Simulates an answer message from the wrapped actor to the test actor.
      *
      * @param response the response message
      * @return this test helper
      */
    def answer(response: Any): ActorWrapperTestHelper = {
      actor.tell(response, probeWrapped.ref)
      this
    }

    /**
      * Expects that the specified message is sent as response from the test
      * actor.
      *
      * @param msg the expected message
      * @return this test helper
      */
    def expectAnswer(msg: Any): ActorWrapperTestHelper = {
      expectMsg(msg)
      this
    }

    /**
      * Stops the probe for the wrapped actor. This is used to test handling
      * of actor termination.
      *
      * @return this test helper
      */
    def stopWrappedActor(): ActorWrapperTestHelper = {
      system stop probeWrapped.ref
      this
    }

    /**
      * Waits until the wrapped actor has propagated its termination message.
      *
      * @return this test helper
      */
    def waitForWrappedActorTermination(): ActorWrapperTestHelper = {
      val watcher = TestProbe()
      watcher watch probeWrapped.ref
      watcher.expectMsgType[Terminated]
      this
    }

    /**
      * Stops the test actor.
      *
      * @return this test helper
      */
    def stopTestActor(): ActorWrapperTestHelper = {
      system stop actor
      this
    }

    /**
      * Creates a test actor reference. Makes sure that the test actor is
      * stopped if an exception occurs.
      *
      * @return the test actor
      */
    private def createTestActor(): ActorRef = {
      val strategy = OneForOneStrategy() {
        case _ => Stop
      }
      val props = Props(classOf[StreamSourceActorWrapper], probeWrapped.ref,
        WrappedActorTerminated)
      val supervisor = SupervisionTestActor(system, strategy, props)
      supervisor.underlyingActor.childActor
    }
  }

}

/**
  * A message to indicate the termination of the wrapped actor.
  */
case object WrappedActorTerminated
