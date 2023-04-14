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

package de.oliver_heger.linedj.player.engine.radio.facade

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, FishingOutcomes}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.AskTimeoutException
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.actors.ActorCreatorForEventManagerTests.{ActorCheckFunc, ClassicActorCheckFunc}
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.player.engine.actors._
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.radio.config.{RadioPlayerConfig, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine.radio.control._
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioDataSourceActor, RadioStreamBuilder}
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}

object RadioPlayerSpec {
  /** The name of the dispatcher for blocking actors. */
  private val BlockingDispatcherName = "TheBlockingDispatcher"
}

/**
  * Test class for ''RadioPlayer''.
  */
class RadioPlayerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {

  import RadioPlayerSpec._

  def this() = this(ActorSystem("RadioPlayerSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  /** The implicit execution context. */
  private implicit val ec: ExecutionContext = system.dispatcher

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
  }

  "A RadioPlayer" should "provide access to its current config" in {
    val helper = new RadioPlayerTestHelper

    helper.player.config should be(helper.config)
  }

  it should "correctly implement the close() method" in {
    val helper = new RadioPlayerTestHelper
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(100.milliseconds)

    intercept[AskTimeoutException] {
      Await.result(helper.player.close(), 1.second)
    }
    helper.probeFacadeActor.expectMsg(CloseRequest)
  }

  it should "pass the event actor to the super class" in {
    val probeListener = testKit.createTestProbe[RadioEvent]()
    val helper = new RadioPlayerTestHelper

    helper.player removeEventListener probeListener.ref

    helper.actorCreator.probeEventActor.fishForMessagePF(3.seconds) {
      case EventManagerActor.RemoveListener(listener) if listener == probeListener.ref =>
        FishingOutcomes.complete
      case _ => FishingOutcomes.continueAndIgnore
    }
  }

  it should "support setting the configuration for radio sources" in {
    val sourcesConfig = mock[RadioSourceConfig]
    val helper = new RadioPlayerTestHelper

    helper.player.initRadioSourceConfig(sourcesConfig)

    helper.expectControlCommand(RadioControlActor.InitRadioSourceConfig(sourcesConfig))
  }

  it should "support switching to another radio source" in {
    val source = RadioSource("newCurrentSource")
    val helper = new RadioPlayerTestHelper

    helper.player.switchToRadioSource(source)

    helper.expectControlCommand(RadioControlActor.SelectRadioSource(source))
  }

  it should "support starting radio playback" in {
    val helper = new RadioPlayerTestHelper

    helper.player.startPlayback()

    helper.expectControlCommand(RadioControlActor.StartPlayback)
  }

  it should "support starting radio playback with a delay" in {
    val Delay = 21.seconds
    val helper = new RadioPlayerTestHelper

    helper.player.startPlayback(Delay)

    val command = helper.expectScheduleCommand()
    command.delay should be(Delay)
    command.invocation.send()
    helper.expectControlCommand(RadioControlActor.StartPlayback)
  }

  it should "support stopping radio playback" in {
    val helper = new RadioPlayerTestHelper

    helper.player.stopPlayback()

    helper.expectControlCommand(RadioControlActor.StopPlayback)
  }

  it should "support stopping radio playback with a delay" in {
    val Delay = 43.seconds
    val helper = new RadioPlayerTestHelper

    helper.player.stopPlayback(Delay)

    val command = helper.expectScheduleCommand()
    command.delay should be(Delay)
    command.invocation.send()
    helper.expectControlCommand(RadioControlActor.StopPlayback)
  }

  it should "create only a single stream builder" in {
    val helper = new RadioPlayerTestHelper

    helper.checkStreamBuilder()
  }

  /**
    * A helper class managing the dependencies of the test radio player
    * instance.
    */
  private class RadioPlayerTestHelper {
    /**
      * The function to check the classic actors created during tests.
      */
    private val classicCheckFunc: ClassicActorCheckFunc = props => {
      case "radioPlayerFacadeActor" =>
        classOf[PlayerFacadeActor] isAssignableFrom props.actorClass() shouldBe true
        classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
        props.args should have size 6
        props.args.take(5) should contain theSameElementsInOrderAs List(config.playerConfig, probePlayerEventActor.ref,
          probeSchedulerInvocationActor.ref, probeFactoryActor.ref, probeLineWriterActor.ref)
        val creator = props.args(5).asInstanceOf[SourceActorCreator]
        checkSourceActorCreator(creator)
        probeFacadeActor.ref
    }

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
      Behaviors.receiveMessagePartial[RadioEventConverterActor.RadioEventConverterCommand] {
        case RadioEventConverterActor.GetPlayerListener(client) =>
          client ! RadioEventConverterActor.PlayerListenerReference(probePlayerEventActor.ref)
          Behaviors.same
      }

    /**
      * The function to check additional typed actors created during tests.
      */
    private val checkFunc: ActorCheckFunc = (behavior, optStopCmd, props) => {
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

      case "radioLineWriterActor" =>
        props should not be Props.empty // It seems impossible to extract the dispatcher name.
        probeLineWriterActor.ref

      case "radioControlActor" =>
        behavior should be(controlBehavior)
        optStopCmd should be(Some(RadioControlActor.Stop))
        probeControlActor.ref
    }

    /** The object for creating test actors. */
    val actorCreator: ActorCreatorForEventManagerTests[RadioEvent] = createActorCreator()

    /** Test probe for the facade actor. */
    val probeFacadeActor: TestProbe = TestProbe()

    /** Test probe for the line writer actor. */
    private val probeLineWriterActor = testKit.createTestProbe[LineWriterActor.LineWriterCommand]()

    /** Test probe for the control actor. */
    private val probeControlActor = testKit.createTestProbe[RadioControlActor.RadioControlCommand]()

    /** The behavior used for the control actor. */
    private val controlBehavior = Behaviors.monitor[RadioControlActor.RadioControlCommand](probeControlActor.ref,
      Behaviors.ignore)

    /**
      * Stores the stream builders passed to different actor creation
      * functions. There should be exactly one builder.
      */
    private val streamBuilders = new ConcurrentHashMap[RadioStreamBuilder, Boolean]()

    /** The test player configuration. */
    val config: RadioPlayerConfig = createPlayerConfig()

    /** The player to be tested. */
    val player: RadioPlayer = futureResult(RadioPlayer(config, createControlActorFactory()))

    /**
      * Expects that the given command was sent to the control actor.
      *
      * @param command the expected command
      */
    def expectControlCommand(command: RadioControlActor.RadioControlCommand): Unit = {
      probeControlActor.expectMessage(command)
    }

    /**
      * Expects that a message is sent to the scheduler actor.
      *
      * @return the received message
      */
    def expectScheduleCommand(): ScheduledInvocationActor.ActorInvocationCommand =
      probeSchedulerInvocationActor.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]

    /**
      * Checks that exactly one stream builder was created and passed to the
      * actors used by the player.
      */
    def checkStreamBuilder(): Unit = {
      streamBuilders should have size 1
    }

    /**
      * Creates a stub [[ActorCreator]] for the configuration of the test
      * player. This implementation checks the parameters passed to actors and
      * returns test probes for them.
      *
      * @return the stub [[ActorCreator]]
      */
    private def createActorCreator(): ActorCreatorForEventManagerTests[RadioEvent] =
      new ActorCreatorForEventManagerTests[RadioEvent](testKit, "radioEventManagerActor",
        customChecks = checkFunc, customClassicChecks = classicCheckFunc) with Matchers

    /**
      * Checks whether a correct function to create the radio source actor has
      * been provided.
      *
      * @param creator the creator function
      */
    private def checkSourceActorCreator(creator: SourceActorCreator): Unit = {
      val probeSourceActor = TestProbe()
      val factory = mock[ChildActorFactory]
      Mockito.when(factory.createChildActor(any())).thenAnswer((invocation: InvocationOnMock) => {
        val props = invocation.getArgument(0, classOf[Props])
        classOf[RadioDataSourceActor] isAssignableFrom props.actorClass() shouldBe true
        classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
        val expectedArguments = List(config.playerConfig, actorCreator.probePublisherActor.ref)
        props.args should have size expectedArguments.size + 1
        props.args.take(2) should contain theSameElementsInOrderAs expectedArguments
        streamBuilders.put(props.args(2).asInstanceOf[RadioStreamBuilder], true)
        probeSourceActor.ref
      })

      val actors = creator(factory, config.playerConfig)
      actors should have size 1
      actors(PlayerFacadeActor.KeySourceActor) should be(probeSourceActor.ref)
    }

    /**
      * Creates a stub factory for the control actor that checks the parameters
      * and returns a mock behavior.
      *
      * @return the factory for the control actor
      */
    private def createControlActorFactory(): RadioControlActor.Factory =
      (radioConfig: RadioPlayerConfig,
       eventActor: typed.ActorRef[RadioEvent],
       eventManagerActor: typed.ActorRef[EventManagerActor.EventManagerCommand[RadioEvent]],
       facadeActor: ActorRef,
       scheduleActor: typed.ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
       factoryActor: typed.ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
       builder: RadioStreamBuilder,
       optEvalService: Option[EvaluateIntervalsService],
       optReplacementService: Option[ReplacementSourceSelectionService],
       optStateService: Option[RadioSourceStateService],
       _: RadioSourceStateActor.Factory,
       _: PlaybackStateActor.Factory,
       _: ErrorStateActor.Factory,
       _: MetadataStateActor.Factory) => {
        radioConfig should be(config)
        eventActor should be(actorCreator.probePublisherActor.ref)
        eventManagerActor should be(actorCreator.eventManagerActor)
        facadeActor should be(probeFacadeActor.ref)
        scheduleActor should be(probeSchedulerInvocationActor.ref)
        factoryActor should be(probeFactoryActor.ref)
        optEvalService shouldBe empty
        optReplacementService shouldBe empty
        optStateService shouldBe empty
        streamBuilders.put(builder, true)

        controlBehavior
      }

    /**
      * Creates a test radio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): RadioPlayerConfig = {
      RadioPlayerConfig(PlayerConfig(mediaManagerActor = null, actorCreator = actorCreator,
        blockingDispatcherName = Some(BlockingDispatcherName)))
    }

    /**
      * Tests whether a correct radio event converter actor is constructed that
      * forwards converted events to the publisher actor.
      *
      * @param behavior the behavior of the converter actor
      */
    private def checkConverterBehavior(behavior: Behavior[RadioEventConverterActor.RadioEventConverterCommand]):
    Unit = {
      val audioSource = AudioSource("https://radio.example.org/stream.mp3", 16384, 0, 0)
      val radioSource = RadioSource(audioSource.uri)
      val playerEvent = PlaybackContextCreationFailedEvent(audioSource)
      val radioEvent = RadioPlaybackContextCreationFailedEvent(radioSource, playerEvent.time)
      val converter = testKit.spawn(behavior)

      converter ! RadioEventConverterActor.ConvertPlayerEvent(playerEvent)

      val commands = actorCreator.probeEventActor.fishForMessagePF(3.seconds) {
        case _: EventManagerActor.Publish[RadioEvent] =>
          FishingOutcomes.complete
        case _ => FishingOutcomes.continueAndIgnore
      }

      commands should have size 1
      commands.head.asInstanceOf[EventManagerActor.Publish[RadioEvent]].event should be(radioEvent)
    }
  }
}
