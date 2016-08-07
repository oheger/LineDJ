/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.util.concurrent.LinkedBlockingQueue

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.RecordingSchedulerSupport
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.DelayActor
import de.oliver_heger.linedj.utils.SchedulerSupport
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object DelayActorSpec {
  /** A test message object. */
  private val Message = new Object
}

/**
  * Test class for ''DelayActorSpec''.
  */
class DelayActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import DelayActorSpec._

  def this() = this(ActorSystem("DelayActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A DelayActor" should "create correct properties" in {
    val props = DelayActor()

    classOf[DelayActor] isAssignableFrom props.actorClass() shouldBe true
    classOf[SchedulerSupport] isAssignableFrom props.actorClass() shouldBe true
    props.args shouldBe 'empty
  }

  it should "forward a message directly if there is no delay" in {
    val helper = new DelayActorTestHelper

    helper.propagate(0.seconds).expectNoSchedule()
    helper.targetProbe.expectMsg(Message)
  }

  it should "create a scheduled invocation if there is a delay" in {
    val helper = new DelayActorTestHelper
    val delay = 10.seconds

    val invocation = helper.propagate(delay).checkNoMessageForTarget().expectSchedule(delay)
    invocation.cancellable.isCancelled shouldBe false
  }

  it should "cancel a pending schedule if another message is received" in {
    val helper = new DelayActorTestHelper
    val delay = 1.minute
    val invocation = helper.propagate(delay).expectSchedule(delay)

    val otherMsg = "another message"
    helper.propagate(0.seconds, msg = otherMsg)
    helper.targetProbe.expectMsg(otherMsg)
    invocation.cancellable shouldBe 'cancelled
  }

  it should "reset a pending cancellable when a new message arrives" in {
    val helper = new DelayActorTestHelper
    val delay = 30.seconds
    val invocation = helper.propagate(delay).expectSchedule(delay)

    helper.propagate(delay = 0.seconds, msg = "foo").propagate(delay = 0.seconds, msg = "bar")
    invocation.cancellable.cancelCount should be(1)
  }

  it should "handle multiple targets" in {
    val helper = new DelayActorTestHelper
    val otherTarget = TestProbe()
    val delay = 45.seconds
    val OtherMessage = "another message"
    val invocation = helper.propagate(delay, target = otherTarget.ref)
      .expectSchedule(delay, target = otherTarget.ref)

    helper.propagate(0.seconds)
    invocation.cancellable.isCancelled shouldBe false
    helper.propagate(0.seconds, target = otherTarget.ref, msg = OtherMessage)
    otherTarget.expectMsg(OtherMessage)
    invocation.cancellable.isCancelled shouldBe true
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

  it should "reset the Cancellable when the invocation is done" in {
    val helper = new DelayActorTestHelper
    val delay = 1.minute
    val invocation = helper.propagate(delay).expectSchedule(delay)
    helper send invocation.message

    helper.propagate(4.minutes)
    invocation.cancellable.isCancelled shouldBe false
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

  it should "cancel pending scheduled invocations on a close request" in {
    val helper = new DelayActorTestHelper
    val otherTarget = TestProbe()
    val delay1 = 1.minute
    val delay2 = 30.minutes
    val invocation1 = helper.propagate(delay1).expectSchedule(delay1)
    val invocation2 = helper.propagate(delay2, target = otherTarget.ref)
      .expectSchedule(delay2, target = otherTarget.ref)

    helper.actor ! CloseRequest
    expectMsg(CloseAck(helper.actor))
    invocation1.cancellable shouldBe 'cancelled
    invocation2.cancellable shouldBe 'cancelled
  }

  it should "remove all delay data after processing a close request" in {
    val helper = new DelayActorTestHelper
    val delay = 1.hour
    val invocation = helper.propagate(delay).expectSchedule(delay)
    helper.actor ! CloseRequest
    expectMsgType[CloseAck]

    helper.propagate(delay)
    invocation.cancellable.cancelCount should be(1)
  }

  /**
    * A test helper class managing a test actor instance and some related
    * objects.
    */
  private class DelayActorTestHelper {
    /** The queue for recording scheduled invocations. */
    private val schedulerQueue =
    new LinkedBlockingQueue[RecordingSchedulerSupport.SchedulerInvocation]

    /** A probe that can serve as target. */
    val targetProbe = TestProbe()

    /** The test actor. */
    val actor = TestActorRef[DelayActor](createProps())

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
      schedulerQueue.isEmpty shouldBe true
      this
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
                       msg: Any = Message): RecordingSchedulerSupport.SchedulerInvocation = {
      val invocation = RecordingSchedulerSupport expectInvocation schedulerQueue
      invocation.receiver should be(actor)
      invocation.interval should be(null)
      invocation.initialDelay should be(delay)
      invocation.message match {
        case d: DelayActor.DelayedInvocation =>
          d.propagate.msg should be(msg)
          d.propagate.target should be(target)
        case m => fail("Unexpected scheduled message: " + m)
      }
      invocation
    }

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
    private def createProps(): Props =
    Props(new DelayActor with RecordingSchedulerSupport {
      override val queue = schedulerQueue
    })
  }

}
