/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.actors

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.DelayActor.Propagate
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{FiniteDuration, _}

object DelayActorSpec {
  /** A test message object. */
  private val Message = new Object
}

/**
  * Test class for ''DelayActorSpec''.
  */
class DelayActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  import DelayActorSpec._

  def this() = this(ActorSystem("DelayActorSpec"))

  /** A test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
  }

  "A DelayActor" should "forward a message directly if there is no delay" in {
    val helper = new DelayActorTestHelper

    helper.propagate(0.seconds).expectNoSchedule()
    helper.targetProbe.expectMsg(Message)
  }

  it should "forward multiple messages directly if there is no delay" in {
    val otherTarget = TestProbe()
    val otherMessage = 42
    val helper = new DelayActorTestHelper
    val propagate = new Propagate(List((Message, helper.targetProbe.ref), (otherMessage, otherTarget.ref)), 0.seconds)

    helper.send(propagate)
      .expectNoSchedule()
    helper.targetProbe.expectMsg(Message)
    otherTarget.expectMsg(otherMessage)
  }

  it should "create a scheduled invocation if there is a delay" in {
    val helper = new DelayActorTestHelper
    val delay = 10.seconds

    helper.propagate(delay).checkNoMessageForTarget().expectSchedule(delay)
  }

  it should "create a scheduled invocation for multiple messages if there is a delay" in {
    val otherTarget = TestProbe()
    val otherMessage = 42
    val helper = new DelayActorTestHelper
    val delay = 2.minutes
    val propagate = new Propagate(List((Message, helper.targetProbe.ref), (otherMessage, otherTarget.ref)), delay)

    helper.send(propagate)
      .checkNoMessageForTarget()
      .expectMultiSchedule(delay, propagate.sendData)
  }

  it should "send multiple messages in order" in {
    val otherMessage = 84
    val helper = new DelayActorTestHelper
    val propagate = new Propagate(List((Message, helper.targetProbe.ref), (otherMessage, helper.targetProbe.ref)),
      0.seconds)

    helper.send(propagate)
      .expectNoSchedule()
    helper.targetProbe.expectMsg(Message)
    helper.targetProbe.expectMsg(otherMessage)
  }

  it should "drop a pending schedule if another message is received" in {
    val helper = new DelayActorTestHelper
    val delay = 1.minute
    val invocation = helper.propagate(delay).expectSchedule(delay)

    val otherMsg = "another message"
    helper.propagate(0.seconds, msg = otherMsg)
    helper.targetProbe.expectMsg(otherMsg)

    helper.send(invocation.message)
      .checkNoMessageForTarget()
  }

  it should "handle multiple targets" in {
    val helper = new DelayActorTestHelper
    val otherTarget = TestProbe()
    val delay = 45.seconds
    val OtherMessage = "another message"
    val invocation = helper.propagate(delay, target = otherTarget.ref, msg = OtherMessage)
      .expectSchedule(delay, target = otherTarget.ref, msg = OtherMessage)

    helper.propagate(0.seconds)
      .send(invocation.message)
    otherTarget.expectMsg(OtherMessage)
    helper.targetProbe.expectMsg(Message)

    val ThirdMessage = "one more message"
    val invocation2 = helper.propagate(delay, target = otherTarget.ref, msg = OtherMessage)
      .expectSchedule(delay, target = otherTarget.ref, msg = OtherMessage)
    helper.propagate(0.seconds, target = otherTarget.ref, msg = ThirdMessage)
    otherTarget.expectMsg(ThirdMessage)
    helper.send(invocation2.message)
      .checkNoMessageForTarget()
  }

  it should "process a DelayedInvocation message" in {
    val helper = new DelayActorTestHelper
    val delay = 1.hour
    val invocation = helper.propagate(delay).expectSchedule(delay)

    helper send invocation.message
    helper.targetProbe.expectMsg(Message)
  }

  it should "support multiple delayed invocations" in {
    val helper = new DelayActorTestHelper
    val delay = 1.hour
    val invocation = helper.propagate(delay).expectSchedule(delay)
    helper send invocation.message
    helper.targetProbe.expectMsg(Message)

    val invocation2 = helper.propagate(delay).expectSchedule(delay)
    helper send invocation2.message
    helper.targetProbe.expectMsg(Message)
  }

  it should "reset data structures when the invocation is done" in {
    val helper = new DelayActorTestHelper
    val delay = 1.minute
    val invocation = helper.propagate(delay).expectSchedule(delay)
    helper send invocation.message
    helper.targetProbe.expectMsg(Message)

    val invocation2 = helper.propagate(delay).expectSchedule(delay)
    helper send invocation2.message
    helper.targetProbe.expectMsg(Message)
  }

  it should "ignore unexpected DelayedInvocation messages" in {
    val helper = new DelayActorTestHelper

    helper.send(DelayActor.DelayedInvocation(DelayActor.Propagate(Message,
      helper.targetProbe.ref, 10.minutes), 0)).checkNoMessageForTarget()
  }

  it should "ignore outdated DelayedInvocation messages" in {
    val helper = new DelayActorTestHelper
    val delay1 = 10.seconds
    val invocation = helper.propagate(delay1).expectSchedule(delay1)

    helper.propagate(20.seconds)
    helper.send(invocation.message).checkNoMessageForTarget()
  }

  it should "drop pending scheduled invocations on a close request" in {
    val helper = new DelayActorTestHelper
    val otherTarget = TestProbe()
    val delay1 = 1.minute
    val delay2 = 30.minutes
    val invocation1 = helper.propagate(delay1).expectSchedule(delay1)
    val invocation2 = helper.propagate(delay2, target = otherTarget.ref)
      .expectSchedule(delay2, target = otherTarget.ref)

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))

    helper.send(invocation1.message)
      .send(invocation2.message)
      .checkNoMessageForTarget()
    otherTarget.expectNoMessage(500.millis)
  }

  /**
    * A test helper class managing a test actor instance and some related
    * objects.
    */
  private class DelayActorTestHelper {
    /** A probe that can serve as target. */
    val targetProbe: TestProbe = TestProbe()

    /** Test probe for the scheduler actor. */
    private val schedulerProbe = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** The test actor. */
    val actor: TestActorRef[DelayActor] = TestActorRef[DelayActor](createProps())

    /**
      * Sends the specified message directly to the test actor.
      *
      * @param msg the message to be sent
      * @return this test helper
      */
    def send(msg: Any): DelayActorTestHelper = {
      actor receive msg
      this
    }

    /**
      * Sends a ''Propagate'' message to the test actor.
      *
      * @param target the target actor
      * @param delay  the delay
      * @param msg    the message
      * @return this test helper
      */
    def propagate(delay: FiniteDuration, target: ActorRef = targetProbe.ref,
                  msg: Any = Message): DelayActorTestHelper = {
      send(DelayActor.Propagate(msg = msg, target = target, delay = delay))
      this
    }

    /**
      * Checks that no scheduler invocation has been created.
      *
      * @return this test helper
      */
    def expectNoSchedule(): DelayActorTestHelper = {
      schedulerProbe.expectNoMessage(500.millis)
      this
    }

    /**
      * Expects a scheduled invocation for multiple messages.
      *
      * @param delay the delay
      * @param data  the data to be sent with a delay
      * @return the scheduled invocation
      */
    def expectMultiSchedule(delay: FiniteDuration, data: Iterable[(Any, ActorRef)]):
    ScheduledInvocationActor.ClassicActorInvocation = {
      val command = schedulerProbe.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      command.delay should be(delay)
      command.invocation match {
        case inv@ScheduledInvocationActor.ClassicActorInvocation(receiver, message) =>
          receiver should be(actor)
          message match {
            case d: DelayActor.DelayedInvocation =>
              d.propagate.sendData should be(data)
            case m => fail("Unexpected scheduled message: " + m)
          }
          inv
        case o => fail("Unexpected invocation: " + o)
      }
    }

    /**
      * Expects a scheduled invocation with the specified parameters.
      *
      * @param target the target actor
      * @param delay  the delay
      * @param msg    the message
      * @return the scheduled invocation
      */
    def expectSchedule(delay: FiniteDuration, target: ActorRef = targetProbe.ref,
                       msg: Any = Message): ScheduledInvocationActor.ClassicActorInvocation =
      expectMultiSchedule(delay, List((msg, target)))

    /**
      * Checks that the target actor did not receive a message.
      *
      * @param target the probe to be checked
      * @return this test helper
      */
    def checkNoMessageForTarget(target: TestProbe = targetProbe): DelayActorTestHelper = {
      val Ping = "TestPingMessage"
      target.ref ! Ping
      target.expectMsg(Ping)
      this
    }

    /**
      * Creates the properties for a test instance.
      *
      * @return the ''Props'' for a test instance
      */
    private def createProps(): Props = DelayActor(schedulerProbe.ref)
  }
}
