/*
 * Copyright 2015-2024 The Developers Team.
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

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.{actor => classic}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq => eqArg}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

/**
  * Test class for [[ScheduledInvocationActor]].
  */
class ScheduledInvocationActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(classic.ActorSystem("ScheduledInvocationActorSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system

    super.afterAll()

  "ScheduledInvocationActor" should "schedule a typed invocation" in:
    val probe = testKit.createTestProbe[String]()
    val message = "A delayed test message"
    val invocation = ScheduledInvocationActor.typedInvocation(probe.ref, message)
    val command = ScheduledInvocationActor.ActorInvocationCommand(90.seconds, invocation)
    val helper = new ScheduledInvocationTestHelper

    helper.testScheduledInvocation(command, command.delay)

    probe.expectMessage(message)

  it should "schedule a classic invocation" in:
    val probe = TestProbe()
    val message = "A delayed classic test message"
    val invocation = ScheduledInvocationActor.ClassicActorInvocation(probe.ref, message)
    val command = ScheduledInvocationActor.ActorInvocationCommand(5.minutes, invocation)
    val helper = new ScheduledInvocationTestHelper

    helper.testScheduledInvocation(command, command.delay)

    probe.expectMsg(message)

  it should "create a correct actor instance" in:
    val probe = testKit.createTestProbe[String]()
    val message = "Real test message"
    val command = ScheduledInvocationActor.typedInvocationCommand(100.millis, probe.ref, message)

    val scheduledInvocationActor = testKit.spawn(ScheduledInvocationActor())
    scheduledInvocationActor ! command

    probe.expectMessage(message)

  it should "stop itself on receiving a Stop command" in:
    val helper = new ScheduledInvocationTestHelper

    helper.testStop()

  /**
    * A test helper class managing an actor under test and its dependencies.
    */
  private class ScheduledInvocationTestHelper:
    /** Mock for the scheduler. */
    private val scheduler = mock[TimerScheduler[ScheduledInvocationActor.ScheduledInvocationCommand]]

    /** The actor to be tested. */
    private val scheduledInvocationActor = testKit.spawn(ScheduledInvocationActor.handleMessages(scheduler))

    /**
      * Executes a test for a scheduled invocation using the given command. The
      * command is send to the test actor, and the interaction with the
      * scheduler is checked. Then the message sent by the scheduler is
      * simulated, so that the scheduled message should actually be sent.
      *
      * @param command the command to execute
      * @param delay   the delay for the invocation
      */
    def testScheduledInvocation(command: ScheduledInvocationActor.ScheduledInvocationCommand,
                                delay: FiniteDuration): Unit =
      scheduledInvocationActor ! command

      val capture = ArgumentCaptor.forClass(classOf[ScheduledInvocationActor.ScheduledInvocationCommand])
      verify(scheduler, timeout(3000)).startSingleTimer(capture.capture(), eqArg(delay))
      scheduledInvocationActor ! capture.getValue

    /**
      * Tests whether the actor can stop itself.
      */
    def testStop(): Unit =
      val probeWatcher = testKit.createDeadLetterProbe()

      scheduledInvocationActor ! ScheduledInvocationActor.Stop

      probeWatcher.expectTerminated(scheduledInvocationActor)
      verify(scheduler, timeout(1000)).cancelAll()
