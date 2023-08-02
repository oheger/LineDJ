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
import akka.util.Timeout
import akka.{actor => classic}
import com.github.cloudfiles.core.http.factory.Spawner
import de.oliver_heger.linedj.player.engine.ActorCreator
import de.oliver_heger.linedj.player.engine.PlayerConfigSpec.TestPlayerConfig
import de.oliver_heger.linedj.player.engine.actors.ScheduledInvocationActor.ScheduledInvocationCommand
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, PlaybackContextFactoryActor}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamManagerActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.Clock
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration._

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

  it should "initialize the metadata config" in {
    val metaConfig = mock[MetadataConfig]
    val initCommand = RadioControlActor.InitMetadataConfig(metaConfig)
    val expMetaStateCommand = MetadataStateActor.InitMetadataConfig(metaConfig)
    val helper = new ControlActorTestHelper

    helper.sendCommand(initCommand)
      .checkMetadataStateCommand(expMetaStateCommand)
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

  it should "handle a command to disable a source" in {
    val source = radioSource(7)
    val helper = new ControlActorTestHelper

    helper.sendEnabledStateCommand(RadioControlProtocol.DisableSource(source))
      .checkSourceStateCommand(RadioSourceStateActor.RadioSourceDisabled(source))
  }

  it should "handle a command to enable a source" in {
    val source = radioSource(11)
    val helper = new ControlActorTestHelper

    helper.sendEnabledStateCommand(RadioControlProtocol.EnableSource(source))
      .checkSourceStateCommand(RadioSourceStateActor.RadioSourceEnabled(source))
  }

  it should "handle a command to query the sources in error state" in {
    val probe = testKit.createTestProbe[ErrorStateActor.SourcesInErrorState]()
    val helper = new ControlActorTestHelper

    helper.sendCommand(RadioControlActor.GetSourcesInErrorState(probe.ref))
      .checkErrorStateCommand(ErrorStateActor.GetSourcesInErrorState(probe.ref))
  }

  it should "create a playback guardian actor" in {
    val helper = new ControlActorTestHelper

    helper.checkGuardianActorCreated()
  }

  it should "handle a Stop command" in {
    val helper = new ControlActorTestHelper

    helper.sendCommand(RadioControlActor.Stop)
      .checkControlActorStopped()
  }

  it should "handle a command to query the current playback state" in {
    val currentSource = radioSource(11)
    val probeClient = testKit.createTestProbe[RadioControlActor.CurrentPlaybackState]()
    val helper = new ControlActorTestHelper

    helper.sendCommand(RadioControlActor.GetPlaybackState(probeClient.ref))

    helper.expectPlaybackStateCommand() match {
      case PlaybackStateActor.GetPlaybackState(replyTo) =>
        replyTo ! PlaybackStateActor.CurrentPlaybackState(Some(currentSource), playbackActive = true)
      case m => fail("Unexpected playback state command: " + m)
    }

    probeClient.expectMessage(RadioControlActor.CurrentPlaybackState(Some(currentSource), playbackActive = true))
  }

  it should "handle a timeout when querying the playback state" in {
    val probeClient = testKit.createTestProbe[RadioControlActor.CurrentPlaybackState]()
    val helper = new ControlActorTestHelper(Timeout(10.millis))

    helper.sendCommand(RadioControlActor.GetPlaybackState(probeClient.ref))

    probeClient.expectMessage(RadioControlActor.CurrentPlaybackState(None, playbackActive = false))
  }

  /**
    * A test helper class managing a control actor under test and its
    * dependencies.
    *
    * @param askTimeout the ask timeout for the test actor
    */
  private class ControlActorTestHelper(askTimeout: Timeout = Timeout(5.seconds)) {
    /** A test configuration used by the control actor. */
    private val config = RadioPlayerConfig(playerConfig = TestPlayerConfig.copy(actorCreator = mock[ActorCreator],
      mediaManagerActor = mock[classic.ActorRef]),
      metadataCheckTimeout = 99.seconds,
      maximumEvalDelay = 2.hours,
      retryFailedReplacement = 1.minute,
      retryFailedSource = 50.seconds,
      retryFailedSourceIncrement = 3.0,
      maxRetryFailedSource = 20.hours,
      sourceCheckTimeout = 1.minute,
      streamCacheTime = 3.seconds,
      stalledPlaybackCheck = 30.seconds)

    /** Test probe for the schedule invocation actor. */
    private val probeScheduleActor = testKit.createTestProbe[ScheduledInvocationCommand]()

    /** Test probe for the playback context factory actor. */
    private val probeFactoryActor = testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** Test probe for the actor for publishing events. */
    private val probeEventActor = testKit.createTestProbe[RadioEvent]()

    /** Test probe for the event manager actor. */
    private val probeEventManagerActor = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the radio source state actor. */
    private val probeStateActor = testKit.createTestProbe[RadioSourceStateActor.RadioSourceStateCommand]()

    /** Test probe for the playback state actor. */
    private val probePlayActor = testKit.createTestProbe[PlaybackStateActor.PlaybackStateCommand]()

    /** Test probe for the error state actor. */
    private val probeErrorStateActor = testKit.createTestProbe[ErrorStateActor.ErrorStateCommand]()

    /** Test probe for the metadata state actor. */
    private val probeMetadataStateActor = testKit.createTestProbe[MetadataStateActor.MetadataExclusionStateCommand]()

    /** Test probe for the stream manager actor. */
    private val probeStreamManagerActor = testKit.createTestProbe[RadioStreamManagerActor.RadioStreamManagerCommand]()

    /** A mock reference for the player facade actor. */
    private val mockFacadeActor = mock[classic.ActorRef]

    /** The reference to the playback actor. */
    private val playActor = new DynamicActorRef[RadioControlProtocol.SwitchToSource]

    /** The reference to the actor managing the source enabled state. */
    private val enabledStateActor = new DynamicActorRef[RadioControlProtocol.SourceEnabledStateCommand](2)

    /** A latch to record the invocation of the guardian actor factory. */
    private val latchGuardianBehaviorCreated = new CountDownLatch(1)

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
      playActor.ref ! RadioControlProtocol.SwitchToSource(source)
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
      * Sends a command to change the enabled state for a specific radio
      * source.
      *
      * @param command the command to be sent
      * @return this test helper
      */
    def sendEnabledStateCommand(command: RadioControlProtocol.SourceEnabledStateCommand): ControlActorTestHelper = {
      enabledStateActor.ref ! command
      this
    }

    /**
      * Expects that a message was passed to the error state actor and returns
      * this message.
      *
      * @return the message passed to the error state actor
      */
    def expectErrorStateCommand(): ErrorStateActor.ErrorStateCommand =
      probeErrorStateActor.expectMessageType[ErrorStateActor.ErrorStateCommand]

    /**
      * Tests that the given command was passed to the error state actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def checkErrorStateCommand(command: ErrorStateActor.ErrorStateCommand): ControlActorTestHelper = {
      expectErrorStateCommand() should be(command)
      this
    }

    /**
      * Tests that the given command was passed to the metadata state actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def checkMetadataStateCommand(command: MetadataStateActor.MetadataExclusionStateCommand):
    ControlActorTestHelper = {
      probeMetadataStateActor.expectMessage(command)
      this
    }

    /**
      * Checks whether the actor under test has been stopped.
      */
    def checkControlActorStopped(): Unit = {
      val probe = testKit.createDeadLetterProbe()
      probe.expectTerminated(controlActor)
    }

    /**
      * Checks whether the playback guardian actor has been created.
      */
    def checkGuardianActorCreated(): Unit = {
      latchGuardianBehaviorCreated.await(3, TimeUnit.SECONDS) shouldBe true
    }

    /**
      * Creates a test control actor instance.
      *
      * @return the actor to be tested
      */
    private def createControlActor(): ActorRef[RadioControlActor.RadioControlCommand] = {
      testKit.spawn(RadioControlActor.behavior(stateActorFactory = createStateActorFactory(),
        playActorFactory = createPlayActorFactory(),
        errorActorFactory = createErrorStateActorFactory(),
        metaActorFactory = createMetadataStateActorFactory(),
        guardianActorFactory = createPlaybackGuardianActorFactory(),
        eventActor = probeEventActor.ref,
        eventManagerActor = probeEventManagerActor.ref,
        facadeActor = mockFacadeActor,
        scheduleActor = probeScheduleActor.ref,
        factoryActor = probeFactoryActor.ref,
        streamManagerActor = probeStreamManagerActor.ref,
        config = config,
        askTimeout = askTimeout))
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
       playActorRef: ActorRef[RadioControlProtocol.SwitchToSource],
       eventActor: ActorRef[RadioEvent]) => {
        stateService shouldBe a[RadioSourceStateServiceImpl]
        stateService.asInstanceOf[RadioSourceStateServiceImpl].config should be(config)
        evalService should be(EvaluateIntervalsServiceImpl)
        replaceService should be(ReplacementSourceSelectionServiceImpl)
        scheduleActor should be(probeScheduleActor.ref)
        eventActor should be(probeEventActor.ref)
        playActor.actorCreated(playActorRef)
        Behaviors.monitor(probeStateActor.ref, Behaviors.ignore)
      }

    /**
      * Creates a factory to create a [[PlaybackStateActor]] which checks the
      * creation parameters and delegates to a test probe.
      *
      * @return the factory for the playback state actor
      */
    private def createPlayActorFactory(): PlaybackStateActor.Factory =
      (facadeActor: classic.ActorRef,
       eventManagerActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]]) => {
        facadeActor should be(mockFacadeActor)
        eventManagerActor should be(probeEventManagerActor.ref)
        Behaviors.monitor(probePlayActor.ref, Behaviors.ignore)
      }

    /**
      * Creates a factory to create an error state actor which checks the
      * creation parameters and delegates to a test probe.
      *
      * @return the factory for the error state actor
      */
    private def createErrorStateActorFactory(): ErrorStateActor.Factory =
      (radioConfig: RadioPlayerConfig,
       enabledActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
       factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
       scheduledInvocationActor: ActorRef[ScheduledInvocationCommand],
       eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
       streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
       _: ErrorStateActor.CheckSchedulerActorFactory,
       _: ErrorStateActor.CheckSourceActorFactory,
       optSpawner: Option[Spawner]) => {
        radioConfig should be(config)
        factoryActor should be(probeFactoryActor.ref)
        scheduledInvocationActor should be(probeScheduleActor.ref)
        eventActor should be(probeEventManagerActor.ref)
        streamManager should be(probeStreamManagerActor.ref)
        optSpawner shouldBe empty

        enabledStateActor.actorCreated(enabledActor)
        Behaviors.monitor(probeErrorStateActor.ref, Behaviors.ignore)
      }

    /**
      * Creates a factory to create a metadata state actor which checks the
      * creation parameters and delegates to a test probe.
      *
      * @return the factory for the metadata state actor
      */
    private def createMetadataStateActorFactory(): MetadataStateActor.Factory =
      (radioConfig: RadioPlayerConfig,
       enabledActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
       scheduleActor: ActorRef[ScheduledInvocationCommand],
       eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
       streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
       intervalService: EvaluateIntervalsService,
       finderService: MetadataExclusionFinderService,
       _: Clock,
       _: MetadataStateActor.SourceCheckFactory) => {
        radioConfig should be(config)
        scheduleActor should be(probeScheduleActor.ref)
        eventActor should be(probeEventManagerActor.ref)
        streamManager should be(probeStreamManagerActor.ref)
        intervalService should be(EvaluateIntervalsServiceImpl)
        finderService match {
          case svc: MetadataExclusionFinderServiceImpl =>
            svc.intervalsService should be(intervalService)
          case o => fail("Unexpected MetadataExclusionFinderService: " + o)
        }

        enabledStateActor.actorCreated(enabledActor)
        Behaviors.monitor(probeMetadataStateActor.ref, Behaviors.ignore)
      }

    /**
      * Creates a factory to create the playback guardian actor which checks
      * the creation parameters and registers the invocation.
      *
      * @return the factory for the playback guardian actor
      */
    private def createPlaybackGuardianActorFactory(): PlaybackGuardianActor.Factory =
      (checkInterval: FiniteDuration,
       enabledActor: ActorRef[RadioControlProtocol.SourceEnabledStateCommand],
       scheduleActor: ActorRef[ScheduledInvocationCommand],
       eventActor: ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]]) => {
        checkInterval should be(config.stalledPlaybackCheck)
        scheduleActor should be(probeScheduleActor.ref)
        eventActor should be(probeEventManagerActor.ref)

        enabledStateActor.actorCreated(enabledActor)
        latchGuardianBehaviorCreated.countDown()
        Behaviors.ignore
      }
  }

  /**
    * A helper class that manages an actor reference created asynchronously. It
    * ensures safe access to the reference from the test thread. The class can
    * also handle references passed to multiple child actors. It is then
    * checked whether always the same reference is used.
    *
    * @param refCount the number of expected references
    * @tparam T the type of the actor reference
    */
  private class DynamicActorRef[T](refCount: Int = 1) {
    private val actorCreationQueue = new ArrayBlockingQueue[ActorRef[T]](refCount)

    private var actorField: ActorRef[T] = _

    def actorCreated(actorRef: ActorRef[T]): Unit = {
      actorCreationQueue offer actorRef
    }

    def ref: ActorRef[T] = {
      if (actorField == null) {
        val references = (1 to refCount).foldLeft(Set.empty[ActorRef[T]]) { (refs, _) =>
          refs + actorCreationQueue.poll(3, TimeUnit.SECONDS)
        }
        references should have size 1
        actorField = references.iterator.next()
        actorField should not be null
      }
      actorField
    }
  }
}
