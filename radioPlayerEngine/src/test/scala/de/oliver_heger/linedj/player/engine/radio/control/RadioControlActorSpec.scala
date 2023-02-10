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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.{actor => classic}
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor.ScheduledInvocationCommand
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource, RadioSourceConfig}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}

/**
  * Test class for [[RadioControlActor]]. This class tests direct interactions
  * between the control actor and its children. The integration with the radio
  * player engine is tested by another spec.
  */
class RadioControlActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {
  "RadioControlActor" should "initialize the radio sources config" in {
    val sourcesConfig = mock[RadioSourceConfig]
    val initCommand = RadioControlActor.InitRadioSourceConfig(sourcesConfig)
    val expStateCommand = RadioSourceStateActor.InitRadioSourceConfig(sourcesConfig)
    val helper = new ControlActorTestHelper

    helper.sendCommand(initCommand)
      .checkSourceStateCommand(expStateCommand)
  }

  it should "support selecting a radio source" in {
    val newSource = radioSource(4)
    val setSourceCommand = RadioControlActor.SelectRadioSource(newSource)
    val expStateCommand = RadioSourceStateActor.RadioSourceSelected(newSource)
    val helper = new ControlActorTestHelper

    helper.sendCommand(setSourceCommand)
      .checkSourceStateCommand(expStateCommand)
  }

  it should "provide an actor to handle SwitchToSource messages" in {
    val nextSource = radioSource(28)
    val helper = new ControlActorTestHelper

    helper.sendSwitchToSourceCommand(nextSource)
      .checkPlaybackStateCommand(PlaybackStateActor.PlaybackSource(nextSource))
  }

  it should "handle a command to start playback" in {
    val helper = new ControlActorTestHelper

    helper.sendCommand(RadioControlActor.StartPlayback)
      .checkPlaybackStateCommand(PlaybackStateActor.StartPlayback)
  }

  it should "handle a command to stop playback" in {
    val helper = new ControlActorTestHelper

    helper.sendCommand(RadioControlActor.StopPlayback)
      .checkPlaybackStateCommand(PlaybackStateActor.StopPlayback)
  }

  /**
    * A test helper class managing a control actor under test and its
    * dependencies.
    */
  private class ControlActorTestHelper {
    /** Test probe for the schedule invocation actor. */
    private val probeScheduleActor = testKit.createTestProbe[ScheduledInvocationCommand]()

    /** Test probe for the actor for publishing events. */
    private val probeEventActor = testKit.createTestProbe[RadioEvent]()

    /** Test probe for the radio source state actor. */
    private val probeStateActor = testKit.createTestProbe[RadioSourceStateActor.RadioSourceStateCommand]()

    /** Test probe for the playback state actor. */
    private val probePlayActor = testKit.createTestProbe[PlaybackStateActor.PlaybackStateCommand]()

    /** A mock reference for the player facade actor. */
    private val mockFacadeActor = mock[classic.ActorRef]

    /** A queue to wait for the creation of the play actor. */
    private val playActorCreationQueue = new ArrayBlockingQueue[ActorRef[RadioControlProtocol.SwitchToSource]](1)

    /** Stores the reference to the playback actor. */
    private var playActorField: ActorRef[RadioControlProtocol.SwitchToSource] = _

    /** The actor instance under test. */
    private val controlActor = createControlActor()

    /**
      * Sends the given command to the test actor.
      *
      * @param command the command
      * @return this test helper
      */
    def sendCommand(command: RadioControlActor.RadioControlCommand): ControlActorTestHelper = {
      controlActor ! command
      this
    }

    /**
      * Expects that a message was passed to the source state actor and returns
      * this message.
      *
      * @return the message passed to the source state actor
      */
    def expectSourceStateCommand(): RadioSourceStateActor.RadioSourceStateCommand =
      probeStateActor.expectMessageType[RadioSourceStateActor.RadioSourceStateCommand]

    /**
      * Tests that the given command was passed to the source state actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def checkSourceStateCommand(command: RadioSourceStateActor.RadioSourceStateCommand): ControlActorTestHelper = {
      expectSourceStateCommand() should be(command)
      this
    }

    /**
      * Sends a command to switch to a radio source to the actor reference
      * passed to the radio source state actor.
      *
      * @param source the affected radio source
      * @return this test helper
      */
    def sendSwitchToSourceCommand(source: RadioSource): ControlActorTestHelper = {
      fetchPlayActor() ! RadioControlProtocol.SwitchToSource(source)
      this
    }

    /**
      * Expects that a message was passed to the playback state actor and
      * returns this message.
      *
      * @return the message passed to the playback state actor
      */
    def expectPlaybackStateCommand(): PlaybackStateActor.PlaybackStateCommand =
      probePlayActor.expectMessageType[PlaybackStateActor.PlaybackStateCommand]

    /**
      * Tests that the given command was passed to the playback state actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def checkPlaybackStateCommand(command: PlaybackStateActor.PlaybackStateCommand): ControlActorTestHelper = {
      expectPlaybackStateCommand() should be(command)
      this
    }

    /**
      * Creates a test control actor instance.
      *
      * @return the actor to be tested
      */
    private def createControlActor(): ActorRef[RadioControlActor.RadioControlCommand] = {
      testKit.spawn(RadioControlActor.behavior(stateActorFactory = createStateActorFactory(),
        playActorFactory = createPlayActorFactory(),
        eventActor = probeEventActor.ref,
        facadeActor = mockFacadeActor,
        scheduleActor = probeScheduleActor.ref))
    }

    /**
      * Creates a factory to create a [[RadioSourceStateActor]]. This factory
      * checks the creation parameters and returns a behavior that delegates to
      * a test probe.
      *
      * @return the factory for the radio source state actor
      */
    private def createStateActorFactory(): RadioSourceStateActor.Factory =
      (stateService: RadioSourceStateService,
       evalService: EvaluateIntervalsService,
       replaceService: ReplacementSourceSelectionService,
       scheduleActor: ActorRef[ScheduledInvocationCommand],
       playActor: ActorRef[RadioControlProtocol.SwitchToSource],
       eventActor: ActorRef[RadioEvent]) => {
        stateService should be(RadioSourceStateServiceImpl)
        evalService should be(EvaluateIntervalsServiceImpl)
        replaceService should be(ReplacementSourceSelectionServiceImpl)
        scheduleActor should be(probeScheduleActor.ref)
        eventActor should be(probeEventActor.ref)
        playActorCreationQueue offer playActor
        Behaviors.monitor(probeStateActor.ref, Behaviors.ignore)
      }

    /**
      * Creates a factory to create a [[PlaybackStateActor]] which checks the
      * creation parameters and delegates to a test probe.
      *
      * @return the factory for the playback state actor
      */
    private def createPlayActorFactory(): PlaybackStateActor.Factory =
      (facadeActor: classic.ActorRef) => {
        facadeActor should be(mockFacadeActor)
        Behaviors.monitor(probePlayActor.ref, Behaviors.ignore)
      }

    /**
      * Obtains the reference to the play actor. Since actor creation is
      * asynchronous, this is a bit tricky. A blocking queue is used to obtain
      * the reference in a safe way.
      *
      * @return the reference to the play actor
      */
    private def fetchPlayActor(): ActorRef[RadioControlProtocol.SwitchToSource] = {
      if (playActorField == null) {
        playActorField = playActorCreationQueue.poll(3, TimeUnit.SECONDS)
        playActorField should not be null
      }
      playActorField
    }
  }
}
