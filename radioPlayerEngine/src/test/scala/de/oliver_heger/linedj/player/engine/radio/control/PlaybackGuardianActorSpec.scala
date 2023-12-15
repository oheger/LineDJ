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

package de.oliver_heger.linedj.player.engine.radio.control

import de.oliver_heger.linedj.ActorTestKitSupport
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

object PlaybackGuardianActorSpec:
  /** The check interval used by test actor instances. */
  private val CheckInterval = 5.seconds

/**
  * Test class for [[PlaybackGuardianActor]].
  */
class PlaybackGuardianActorSpec extends AnyFlatSpec with Matchers with ActorTestKitSupport:

  import PlaybackGuardianActorSpec.*

  "PlaybackGuardianActor" should "schedule a check when playback starts" in:
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(radioSource(1)))
      .expectScheduledInvocation(execute = false)

  it should "not schedule a check if only the source is changed" in:
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(radioSource(1)))
      .expectScheduledInvocation(execute = false)
      .sendEvent(RadioSourceChangedEvent(radioSource(2)))
      .expectNoScheduledInvocation()

  it should "ignore unrelated radio events" in:
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioPlaybackErrorEvent(radioSource(1)))
      .sendEvent(RadioSourceChangedEvent(radioSource(2)))
      .expectScheduledInvocation(execute = false)

  it should "disable a radio source if no progress event arrives" in:
    val source = radioSource(11)
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(source))
      .expectScheduledInvocation(execute = true)
      .expectStateCommand(RadioControlProtocol.DisableSource(source))

  it should "reset the current source when it is disabled" in:
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(radioSource(1)))
      .expectScheduledInvocation(execute = true)
      .expectNoScheduledInvocation()
      .sendEvent(RadioSourceChangedEvent(radioSource(2)))
      .expectScheduledInvocation(execute = false)

  it should "not disable the current source if a playback progress event was received" in:
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(radioSource(1)))
      .sendEvent(RadioPlaybackProgressEvent(radioSource(2), 0, 1.second))
      .expectScheduledInvocation(execute = true)
      .expectNoStateCommand()

  it should "reschedule checks" in:
    val source = radioSource(1)
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(source))
      .sendEvent(RadioPlaybackProgressEvent(source, 1, 100.millis))
      .expectScheduledInvocation(execute = true)
      .expectScheduledInvocation(execute = true)
      .expectStateCommand(RadioControlProtocol.DisableSource(source))

  it should "enable sources again on receiving a playback progress event" in:
    val source1 = radioSource(1)
    val source2 = radioSource(2)
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(source1))
      .expectScheduledInvocation(execute = true)
      .expectStateCommand(RadioControlProtocol.DisableSource(source1))
      .sendEvent(RadioSourceChangedEvent(source2))
      .expectScheduledInvocation(execute = true)
      .expectStateCommand(RadioControlProtocol.DisableSource(source2))
      .sendEvent(RadioSourceChangedEvent(radioSource(3)))
      .sendEvent(RadioPlaybackProgressEvent(radioSource(4), 5, 333.millis))
      .expectStateCommand(RadioControlProtocol.EnableSource(source2))
      .expectStateCommand(RadioControlProtocol.EnableSource(source1))

  it should "clear disabled sources after they have been re-enabled" in:
    val source = radioSource(1)
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(source))
      .expectScheduledInvocation(execute = true)
      .expectStateCommand(RadioControlProtocol.DisableSource(source))
      .sendEvent(RadioSourceChangedEvent(source))
      .sendEvent(RadioPlaybackProgressEvent(source, 8, 100.millis))
      .expectStateCommand(RadioControlProtocol.EnableSource(source))
      .sendEvent(RadioPlaybackProgressEvent(source, 16, 200.millis))
      .expectNoStateCommand()

  it should "handle a playback stopped event" in:
    val helper = new GuardianTestHelper

    helper.sendEvent(RadioSourceChangedEvent(radioSource(1)))
      .sendEvent(RadioPlaybackStoppedEvent(radioSource(1)))
      .expectScheduledInvocation(execute = true)
      .expectNoStateCommand()
      .expectNoScheduledInvocation()

  /**
    * A test helper class managing a test actor reference and its dependencies.
    */
  private class GuardianTestHelper:
    /** Test probe for the enabled state actor. */
    private val probeStateActor = testKit.createTestProbe[RadioControlProtocol.SourceEnabledStateCommand]()

    /** Test probe for the scheduler actor. */
    private val probeSchedulerActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Stores the listener registered at the event actor. */
    private var eventListener: ActorRef[RadioEvent] = _

    createGuardianActor()

    /**
      * Sends the given event to the event listener that the guardian actor has
      * registered.
      *
      * @param event the event to send
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): GuardianTestHelper =
      eventListener ! event
      this

    /**
      * Expects that a scheduled invocation has been triggered at the scheduler
      * actor. Optionally, the invocation can be executed directly.
      *
      * @param execute flag whether the invocation should be executed right now
      * @return this test helper
      */
    def expectScheduledInvocation(execute: Boolean): GuardianTestHelper =
      val command = probeSchedulerActor.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      command.delay should be(CheckInterval)
      if execute then
        command.invocation.send()
      this

    /**
      * Expects that no message has been sent to the scheduler actor.
      *
      * @return this test helper
      */
    def expectNoScheduledInvocation(): GuardianTestHelper =
      expectNoMessage(probeSchedulerActor)

    /**
      * Expects that the given command has been sent to the enabled state
      * actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectStateCommand(command: RadioControlProtocol.SourceEnabledStateCommand): GuardianTestHelper =
      probeStateActor.expectMessage(command)
      this

    /**
      * Expects that no command has been sent to the enabled state actor.
      *
      * @return this test helper
      */
    def expectNoStateCommand(): GuardianTestHelper =
      expectNoMessage(probeStateActor)

    /**
      * Helper function to check whether the given test probe did not receive a
      * message.
      *
      * @param probe the probe
      * @tparam M the message type of the probe
      * @return this test helper
      */
    private def expectNoMessage[M](probe: TestProbe[M]): GuardianTestHelper =
      probe.expectNoMessage(250.millis)
      this

    /**
      * Creates the actor to be tested.
      *
      * @return the test actor reference
      */
    private def createGuardianActor(): ActorRef[PlaybackGuardianActor.PlaybackGuardianCommand] =
      val probeEventActor = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()
      val guardianBehavior = PlaybackGuardianActor.behavior(CheckInterval,
        probeStateActor.ref,
        probeSchedulerActor.ref,
        probeEventActor.ref)
      val guardianActor = testKit.spawn(guardianBehavior)

      val registration = probeEventActor.expectMessageType[EventManagerActor.RegisterListener[RadioEvent]]
      eventListener = registration.listener
      guardianActor
