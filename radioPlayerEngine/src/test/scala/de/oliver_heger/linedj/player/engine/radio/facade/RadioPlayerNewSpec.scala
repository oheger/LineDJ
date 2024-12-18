/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.radio.facade

import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.player.engine.*
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.radio.Fixtures.TestPlayerConfig
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.control.*
import de.oliver_heger.linedj.player.engine.radio.stream.*
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, FishingOutcomes}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props, typed}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.reflect.ClassTag

object RadioPlayerNewSpec:
  /** The name of the dispatcher for blocking actors. */
  private val BlockingDispatcherName = "TheBlockingDispatcher"

/**
  * Test class for ''RadioPlayer''.
  */
class RadioPlayerNewSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper:

  import RadioPlayerNewSpec.*

  def this() = this(ActorSystem("RadioPlayerSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  /** The implicit execution context. */
  private implicit val ec: ExecutionContext = system.dispatcher

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()

  "A RadioPlayer" should "provide access to its current config" in :
    val helper = new RadioPlayerTestHelper

    helper.player.config should be(helper.config)

  it should "correctly implement the close() method" in :
    val helper = new RadioPlayerTestHelper
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(500.milliseconds)

    // Can only check that no exception occurs.
    futureResult(helper.player.close())

  it should "pass the event actor to the super class" in :
    val probeListener = testKit.createTestProbe[RadioEvent]()
    val helper = new RadioPlayerTestHelper

    helper.player removeEventListener probeListener.ref

    helper.actorCreator.probeEventActor.fishForMessagePF(3.seconds):
      case EventManagerActor.RemoveListener(listener) if listener == probeListener.ref =>
        FishingOutcomes.complete
      case _ => FishingOutcomes.continueAndIgnore

  it should "support setting the configuration for radio sources" in :
    val sourcesConfig = mock[RadioSourceConfig]
    val helper = new RadioPlayerTestHelper

    helper.player.initRadioSourceConfig(sourcesConfig)

    helper.expectControlCommand(RadioControlActorNew.InitRadioSourceConfig(sourcesConfig))

  it should "support setting the metadata configuration" in :
    val metaConfig = mock[MetadataConfig]
    val helper = new RadioPlayerTestHelper

    helper.player.initMetadataConfig(metaConfig)

    helper.expectControlCommand(RadioControlActorNew.InitMetadataConfig(metaConfig))

  it should "support switching to another radio source" in :
    val source = RadioSource("newCurrentSource")
    val helper = new RadioPlayerTestHelper

    helper.player.switchToRadioSource(source)

    helper.expectControlCommand(RadioControlActorNew.SelectRadioSource(source))

  it should "support starting radio playback" in :
    val helper = new RadioPlayerTestHelper

    helper.player.startPlayback()

    helper.expectControlCommand(RadioControlActorNew.StartPlayback)

  it should "support starting radio playback with a delay" in :
    val Delay = 21.seconds
    val helper = new RadioPlayerTestHelper

    helper.player.startPlayback(Delay)

    val command = helper.expectScheduleCommand()
    command.delay should be(Delay)
    command.invocation.send()
    helper.expectControlCommand(RadioControlActorNew.StartPlayback)

  it should "support stopping radio playback" in :
    val helper = new RadioPlayerTestHelper

    helper.player.stopPlayback()

    helper.expectControlCommand(RadioControlActorNew.StopPlayback)

  it should "support stopping radio playback with a delay" in :
    val Delay = 43.seconds
    val helper = new RadioPlayerTestHelper

    helper.player.stopPlayback(Delay)

    val command = helper.expectScheduleCommand()
    command.delay should be(Delay)
    command.invocation.send()
    helper.expectControlCommand(RadioControlActorNew.StopPlayback)

  it should "create only a single stream builder" in :
    val helper = new RadioPlayerTestHelper

    helper.checkStreamManager()

  it should "return the current playback state" in :
    val playbackState = RadioControlActorNew.CurrentPlaybackState(Some(RadioSource("testSource")),
      Some(RadioSource("selectedSource")), playbackActive = true, Some(CurrentMetadata("artist / title")))
    val helper = new RadioPlayerTestHelper

    val futState = helper.player.currentPlaybackState

    val getCommand = helper.nextControlCommand[RadioControlActorNew.GetPlaybackState]
    getCommand.replyTo ! playbackState
    futureResult(futState) should be(playbackState)

  /**
    * A helper class managing the dependencies of the test radio player
    * instance.
    */
  private class RadioPlayerTestHelper:
    /** Test probe for the actor converting player to radio events. */
    private val probePlayerEventActor = testKit.createTestProbe[PlayerEvent]()

    /** Test probe for the scheduler invocation actor. */
    private val probeSchedulerInvocationActor =
      testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the playback context factory actor. */
    private val probeFactoryActor = testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /**
      * A stub behavior simulating the radio event converter actor. This
      * behavior supports querying the listener for player events.
      */
    private val mockEventConverterBehavior =
      Behaviors.receiveMessagePartial[RadioEventConverterActor.RadioEventConverterCommand]:
        case RadioEventConverterActor.GetPlayerListener(client) =>
          client ! RadioEventConverterActor.PlayerListenerReference(probePlayerEventActor.ref)
          Behaviors.same

    /**
      * The function to check additional typed actors created during tests.
      */
    private val checkFunc: ActorCreatorForEventManagerTests.ActorCheckFunc = (behavior, optStopCmd, props) => {
      case "playerEventConverter" =>
        optStopCmd should be(Some(RadioEventConverterActor.Stop))
        checkConverterBehavior(behavior.asInstanceOf[Behavior[RadioEventConverterActor.RadioEventConverterCommand]])
        testKit.spawn(mockEventConverterBehavior)

      case "radioSchedulerInvocationActor" =>
        optStopCmd should be(Some(ScheduledInvocationActor.Stop))
        probeSchedulerInvocationActor.ref

      case "radioPlaybackContextFactoryActor" =>
        optStopCmd should be(Some(PlaybackContextFactoryActor.Stop))
        probeFactoryActor.ref

      case "radioStreamManagerActor" =>
        behavior should be(streamManagerBehavior)
        optStopCmd should be(Some(RadioStreamManagerActor.Stop))
        probeStreamManagerActor.ref

      case "radioStreamHandleManagerActor" =>
        behavior should be(streamHandleManagerBehavior)
        optStopCmd should be(Some(RadioStreamHandleManagerActor.Stop))
        probeStreamHandleManagerActor.ref

      case "radioStreamPlaybackActor" =>
        behavior should be(playbackActorBehavior)
        optStopCmd should be(Some(RadioStreamPlaybackActor.Stop))
        probePlaybackActor.ref

      case "radioControlActor" =>
        behavior should be(controlBehavior)
        optStopCmd should be(Some(RadioControlActorNew.Stop))
        probeControlActor.ref
    }

    /** The object for creating test actors. */
    val actorCreator: ActorCreatorForEventManagerTests[RadioEvent] = createActorCreator()

    /** Test probe for the line writer actor. */
    private val probeLineWriterActor = testKit.createTestProbe[LineWriterActor.LineWriterCommand]()

    /** Test probe for the stream manager actor. */
    private val probeStreamManagerActor = testKit.createTestProbe[RadioStreamManagerActor.RadioStreamManagerCommand]()

    /** The behavior for the stream manager actor. */
    private val streamManagerBehavior =
      Behaviors.monitor[RadioStreamManagerActor.RadioStreamManagerCommand](probeStreamManagerActor.ref,
        Behaviors.ignore)

    /** Test probe for the stream handle manager actor. */
    private val probeStreamHandleManagerActor =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

    /** The behavior for the stream handle manager actor. */
    private val streamHandleManagerBehavior =
      Behaviors.monitor[RadioStreamHandleManagerActor.RadioStreamHandleCommand](probeStreamHandleManagerActor.ref,
        Behaviors.ignore)

    /** Test probe for the playback actor. */
    private val probePlaybackActor = testKit.createTestProbe[RadioStreamPlaybackActor.RadioStreamPlaybackCommand]()

    /** The behavior for the playback actor. */
    private val playbackActorBehavior =
      Behaviors.monitor[RadioStreamPlaybackActor.RadioStreamPlaybackCommand](probePlaybackActor.ref,
        Behaviors.ignore)

    /** Test probe for the control actor. */
    private val probeControlActor = testKit.createTestProbe[RadioControlActorNew.RadioControlCommand]()

    /** The behavior used for the control actor. */
    private val controlBehavior = Behaviors.monitor[RadioControlActorNew.RadioControlCommand](probeControlActor.ref,
      Behaviors.ignore)

    /**
      * Stores the stream managers passed to different actor creation
      * functions. There should be exactly one manager.
      */
    private val streamManagers =
      new ConcurrentHashMap[typed.ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand], Boolean]()

    /** The test player configuration. */
    val config: RadioPlayerConfig = createPlayerConfig()

    /** The player to be tested. */
    val player: RadioPlayerNew = futureResult(RadioPlayerNew(config,
      createStreamManagerActorFactory(),
      createStreamHandleManagerActorFactory(),
      createPlaybackActorFactory(),
      createControlActorFactory()))

    /**
      * Expects that a message of the given type is passed to the control
      * actor.
      *
      * @tparam T the message type
      * @return the message that was received
      */
    def nextControlCommand[T <: RadioControlActorNew.RadioControlCommand](implicit t: ClassTag[T]): T =
      probeControlActor.expectMessageType[T]

    /**
      * Expects that the given command was sent to the control actor.
      *
      * @param command the expected command
      */
    def expectControlCommand(command: RadioControlActorNew.RadioControlCommand): Unit =
      probeControlActor.expectMessage(command)

    /**
      * Expects that a message is sent to the scheduler actor.
      *
      * @return the received message
      */
    def expectScheduleCommand(): ScheduledInvocationActor.ActorInvocationCommand =
      probeSchedulerInvocationActor.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]

    /**
      * Checks that exactly one stream manager was created and passed to the
      * actors used by the player.
      */
    def checkStreamManager(): Unit =
      streamManagers should have size 1

    /**
      * Creates a stub [[ActorCreator]] for the configuration of the test
      * player. This implementation checks the parameters passed to actors and
      * returns test probes for them.
      *
      * @return the stub [[ActorCreator]]
      */
    private def createActorCreator(): ActorCreatorForEventManagerTests[RadioEvent] =
      new ActorCreatorForEventManagerTests[RadioEvent](testKit, "radioEventManagerActor",
        customChecks = checkFunc) with Matchers

    /**
      * Checks whether a correct function to create the radio source actor has
      * been provided.
      *
      * @param creator the creator function
      */
    private def checkSourceActorCreator(creator: SourceActorCreator): Unit =
      val probeSourceActor = TestProbe()
      val factory = mock[ChildActorFactory]
      Mockito.when(factory.createChildActor(any())).thenAnswer((invocation: InvocationOnMock) => {
        val props = invocation.getArgument(0, classOf[Props])
        classOf[RadioDataSourceActor] isAssignableFrom props.actorClass() shouldBe true
        val expectedArguments = List(config.playerConfig, actorCreator.probePublisherActor.ref)
        props.args should have size expectedArguments.size + 1
        props.args.take(2) should contain theSameElementsInOrderAs expectedArguments
        streamManagers.put(props.args(2)
          .asInstanceOf[typed.ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand]], true)
        probeSourceActor.ref
      })

      val actors = creator(factory, config.playerConfig)
      actors should have size 1
      actors(PlayerFacadeActor.KeySourceActor) should be(probeSourceActor.ref)

    /**
      * Creates a stub factory for the stream manager actor that checks the
      * parameters and returns a mock behavior.
      *
      * @return the factory for the stream manager actor
      */
    private def createStreamManagerActorFactory(): RadioStreamManagerActor.Factory =
      (playerConfig: PlayerConfig,
       streamBuilder: RadioStreamBuilder,
       scheduler: typed.ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
       cacheTime: FiniteDuration) => {
        playerConfig should be(config.playerConfig)
        streamBuilder should not be null
        scheduler should be(probeSchedulerInvocationActor.ref)
        cacheTime should be(config.streamCacheTime)

        streamManagerBehavior
      }

    /**
      * Creates the stub factory for the stream handle manager actor that 
      * checks the parameters and returns a mock behavior.
      *
      * @return the factory for the stream handle manager actor
      */
    private def createStreamHandleManagerActorFactory(): RadioStreamHandleManagerActor.Factory =
      (streamBuilder: RadioStreamBuilder,
       handleFactory: RadioStreamHandle.Factory,
       scheduler: typed.ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
       cacheTime: FiniteDuration,
       bufferSize: Int) => {
        streamBuilder should not be null
        handleFactory should not be null
        scheduler should be(probeSchedulerInvocationActor.ref)
        cacheTime should be(config.streamCacheTime)
        bufferSize should be(RadioStreamBuilder.DefaultBufferSize)

        streamHandleManagerBehavior
      }

    /**
      * Creates the stub factory for the stream playback actor that checks the
      * parameters and returns a mock behavior.
      *
      * @return the factory for the stream playback actor
      */
    private def createPlaybackActorFactory(): RadioStreamPlaybackActor.Factory =
      (actorConfig: RadioStreamPlaybackActor.RadioStreamPlaybackConfig) =>
        actorConfig.eventActor should be(actorCreator.eventManagerActor)
        actorConfig.handleActor should be(probeStreamHandleManagerActor.ref)
        actorConfig.inMemoryBufferSize should be(config.playerConfig.inMemoryBufferSize)
        actorConfig.dispatcherName should be(BlockingDispatcherName)
        actorConfig.optStreamFactoryLimit should be(Some(config.playerConfig.playbackContextLimit))

        playbackActorBehavior

    /**
      * Creates a stub factory for the control actor that checks the parameters
      * and returns a mock behavior.
      *
      * @return the factory for the control actor
      */
    private def createControlActorFactory(): RadioControlActorNew.Factory =
      (radioConfig: RadioPlayerConfig,
       eventActor: typed.ActorRef[RadioEvent],
       eventManagerActor: typed.ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
       playbackActor: typed.ActorRef[RadioStreamPlaybackActor.RadioStreamPlaybackCommand],
       scheduleActor: typed.ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
       factoryActor: typed.ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
       streamManagerActor: typed.ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
       optEvalService: Option[EvaluateIntervalsService],
       optReplacementService: Option[ReplacementSourceSelectionService],
       optStateService: Option[RadioSourceStateService],
       _: Timeout,
       _: RadioSourceStateActor.Factory,
       _: PlaybackStateActorNew.Factory,
       _: ErrorStateActor.Factory,
       _: MetadataStateActor.Factory) => {
        radioConfig should be(config)
        eventActor should be(actorCreator.probePublisherActor.ref)
        eventManagerActor should be(actorCreator.eventManagerActor)
        playbackActor should be(probePlaybackActor.ref)
        scheduleActor should be(probeSchedulerInvocationActor.ref)
        factoryActor should be(probeFactoryActor.ref)
        optEvalService shouldBe empty
        optReplacementService shouldBe empty
        optStateService shouldBe empty
        streamManagers.put(streamManagerActor, true)

        controlBehavior
      }

    /**
      * Creates a test radio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): RadioPlayerConfig =
      RadioPlayerConfig(TestPlayerConfig.copy(actorCreator = actorCreator,
        blockingDispatcherName = Some(BlockingDispatcherName)),
        metadataCheckTimeout = 99.seconds,
        maximumEvalDelay = 2.hours,
        retryFailedReplacement = 1.minute,
        retryFailedSource = 50.seconds,
        retryFailedSourceIncrement = 3.0,
        maxRetryFailedSource = 20.hours,
        sourceCheckTimeout = 1.minute,
        streamCacheTime = 3.seconds,
        stalledPlaybackCheck = 30.seconds)

    /**
      * Tests whether a correct radio event converter actor is constructed that
      * forwards converted events to the publisher actor.
      *
      * @param behavior the behavior of the converter actor
      */
    private def checkConverterBehavior(behavior: Behavior[RadioEventConverterActor.RadioEventConverterCommand]):
    Unit =
      val audioSource = AudioSource("https://radio.example.org/stream.mp3", 16384, 0, 0)
      val radioSource = RadioSource(audioSource.uri)
      val playerEvent = PlaybackContextCreationFailedEvent(audioSource)
      val radioEvent = RadioPlaybackContextCreationFailedEvent(radioSource, playerEvent.time)
      val converter = testKit.spawn(behavior)

      converter ! RadioEventConverterActor.ConvertPlayerEvent(playerEvent)

      val commands = actorCreator.probeEventActor.fishForMessagePF(3.seconds):
        case _: EventManagerActor.Publish[RadioEvent] =>
          FishingOutcomes.complete
        case _ => FishingOutcomes.continueAndIgnore

      commands should have size 1
      commands.head.asInstanceOf[EventManagerActor.Publish[RadioEvent]].event should be(radioEvent)
