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
import akka.actor.{ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.player.engine.actors.ActorCreatorForEventManagerTests.{ActorCheckFunc, ClassicActorCheckFunc}
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.player.engine.actors._
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.radio.actors.schedule.RadioSchedulerActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioPlaybackContextCreationFailedEvent, RadioSource, RadioSourceConfig}
import de.oliver_heger.linedj.player.engine._
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.utils.{ChildActorFactory, SchedulerSupport}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

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

  "A RadioPlayer" should "support switching the radio source" in {
    val source = RadioSource("Radio Download")
    val helper = new RadioPlayerTestHelper

    helper.player makeToCurrentSource source
    helper.probeSchedulerActor.expectMsg(source)
  }

  it should "provide access to its current config" in {
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
    helper.probeSchedulerActor.expectMsg(CloseRequest)
  }

  it should "support exclusions for radio sources" in {
    val sourcesConfig = mock[RadioSourceConfig]
    val helper = new RadioPlayerTestHelper

    helper.player.initSourceExclusions(sourcesConfig)
    helper.probeSchedulerActor.expectMsg(RadioSchedulerActor.RadioSourceData(sourcesConfig))
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

  it should "support a check for the current radio source with a delay" in {
    val helper = new RadioPlayerTestHelper
    val Delay = 2.minutes
    val Exclusions = Set(RadioSource("ex1"), RadioSource("ex2"))

    helper.player.checkCurrentSource(Exclusions, Delay)
    helper.expectDelayed(RadioSchedulerActor.CheckCurrentSource(Exclusions), helper
      .probeSchedulerActor, Delay)
  }

  it should "support a check for the current radio source with default delay" in {
    val helper = new RadioPlayerTestHelper
    val Exclusions = Set.empty[RadioSource]

    helper.player checkCurrentSource Exclusions
    helper.expectDelayed(RadioSchedulerActor.CheckCurrentSource(Exclusions), helper
      .probeSchedulerActor, PlayerControl.NoDelay)
  }

  it should "support starting playback of a specific radio source" in {
    val source = RadioSource("nice music")
    val helper = new RadioPlayerTestHelper
    val expSrcMsg = PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader())
    val expStartMsg = PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor)
    val expMsg = DelayActor.Propagate(List((expSrcMsg, helper.probeFacadeActor.ref),
      (expStartMsg, helper.probeFacadeActor.ref)), 0.seconds)

    helper.player.playSource(source, makeCurrent = false, resetEngine = false)
    helper.expectDelayed(expMsg)
  }

  it should "support starting playback and making a source the current one" in {
    val source = RadioSource("new current source")
    val helper = new RadioPlayerTestHelper
    val expSrcMsg = PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader())
    val expStartMsg = PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor)
    val expMsg = DelayActor.Propagate(List((source, helper.probeSchedulerActor.ref),
      (expSrcMsg, helper.probeFacadeActor.ref), (expStartMsg, helper.probeFacadeActor.ref)), 0.seconds)

    helper.player.playSource(source, makeCurrent = true, resetEngine = false)
    helper.expectDelayed(expMsg)
  }

  it should "support starting playback and resetting the engine" in {
    val source = RadioSource("source with reset")
    val helper = new RadioPlayerTestHelper
    val expSrcMsg = PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader())
    val expStartMsg = PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor)
    val expMsg = DelayActor.Propagate(List((PlayerFacadeActor.ResetEngine, helper.probeFacadeActor.ref),
      (expSrcMsg, helper.probeFacadeActor.ref), (expStartMsg, helper.probeFacadeActor.ref)), 0.seconds)

    helper.player.playSource(source, makeCurrent = false)
    helper.expectDelayed(expMsg)
  }

  it should "support starting playback, resetting the engine, and making a source the current one" in {
    val source = RadioSource("new current source with reset")
    val helper = new RadioPlayerTestHelper
    val expSrcMsg = PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader())
    val expStartMsg = PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor)
    val expMsg = DelayActor.Propagate(List((PlayerFacadeActor.ResetEngine, helper.probeFacadeActor.ref),
      (source, helper.probeSchedulerActor.ref), (expSrcMsg, helper.probeFacadeActor.ref),
      (expStartMsg, helper.probeFacadeActor.ref)), 0.seconds)

    helper.player.playSource(source, makeCurrent = true)
    helper.expectDelayed(expMsg)
  }

  it should "support starting playback with a delay" in {
    val delay = 22.seconds
    val source = RadioSource("delayed source")
    val helper = new RadioPlayerTestHelper
    val expSrcMsg = PlayerFacadeActor.Dispatch(source, PlayerFacadeActor.TargetSourceReader())
    val expStartMsg = PlayerFacadeActor.Dispatch(PlaybackActor.StartPlayback, PlayerFacadeActor.TargetPlaybackActor)
    val expMsg = DelayActor.Propagate(List((expSrcMsg, helper.probeFacadeActor.ref),
      (expStartMsg, helper.probeFacadeActor.ref)), delay)

    helper.player.playSource(source, makeCurrent = false, resetEngine = false, delay = delay)
    helper.expectDelayed(expMsg)
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
      case "radioLineWriterActor" =>
        classOf[LineWriterActor] isAssignableFrom props.actorClass() shouldBe true
        props.dispatcher should be(BlockingDispatcherName)
        props.args should have size 0
        probeLineWriterActor.ref

      case "radioSchedulerActor" =>
        classOf[RadioSchedulerActor] isAssignableFrom props.actorClass() shouldBe true
        classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
        classOf[SchedulerSupport] isAssignableFrom props.actorClass() shouldBe true
        props.args should have length 1
        props.args.head should be(actorCreator.probePublisherActor.ref)
        probeSchedulerActor.ref

      case "radioPlayerFacadeActor" =>
        classOf[PlayerFacadeActor] isAssignableFrom props.actorClass() shouldBe true
        classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
        props.args should have size 5
        props.args.take(4) should contain theSameElementsInOrderAs List(config, probePlayerEventActor.ref,
          probeSchedulerInvocationActor.ref, probeLineWriterActor.ref)
        val creator = props.args(4).asInstanceOf[SourceActorCreator]
        checkSourceActorCreator(creator)
        probeFacadeActor.ref
    }

    /** Test probe for the actor converting player to radio events. */
    private val probePlayerEventActor = testKit.createTestProbe[PlayerEvent]()

    /** Test probe for the scheduler invocation actor. */
    private val probeSchedulerInvocationActor =
      testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

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
    private val checkFunc: ActorCheckFunc = (behavior, optStopCmd) => {
      case "playerEventConverter" =>
        optStopCmd should be(Some(RadioEventConverterActor.Stop))
        checkConverterBehavior(behavior.asInstanceOf[Behavior[RadioEventConverterActor.RadioEventConverterCommand]])
        testKit.spawn(mockEventConverterBehavior)

      case "radioSchedulerInvocationActor" =>
        optStopCmd should be(Some(ScheduledInvocationActor.Stop))
        probeSchedulerInvocationActor.ref
    }

    /** The object for creating test actors. */
    val actorCreator: ActorCreatorForEventManagerTests[RadioEvent] = createActorCreator()

    /** Test probe for the facade actor. */
    val probeFacadeActor: TestProbe = TestProbe()

    /** Test probe for the line writer actor. */
    val probeLineWriterActor: TestProbe = TestProbe()

    /** Test probe for the scheduler actor. */
    val probeSchedulerActor: TestProbe = TestProbe()

    /** The test player configuration. */
    val config: PlayerConfig = createPlayerConfig()

    /** The player to be tested. */
    val player: RadioPlayer = futureResult(RadioPlayer(config))

    /**
      * Expect a delayed invocation via the delay actor.
      *
      * @param msg    the message
      * @param target the target test probe
      * @param delay  the delay
      */
    def expectDelayed(msg: Any, target: TestProbe, delay: FiniteDuration): Unit = {
      expectDelayed(DelayActor.Propagate(msg, target.ref, delay))
    }

    /**
      * Expects that a specific ''Propagate'' message is passed to the facade
      * actor.
      *
      * @param prop the expected ''Propagate'' message
      */
    def expectDelayed(prop: DelayActor.Propagate): Unit = {
      probeFacadeActor.expectMsg(prop)
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
        props.args should be(List(config, actorCreator.probePublisherActor.ref))
        probeSourceActor.ref
      })

      val actors = creator(factory, config)
      actors should have size 1
      actors(PlayerFacadeActor.KeySourceActor) should be(probeSourceActor.ref)
    }

    /**
      * Creates a test audio player configuration.
      *
      * @return the test configuration
      */
    private def createPlayerConfig(): PlayerConfig =
      PlayerConfig(mediaManagerActor = null, actorCreator = actorCreator,
        blockingDispatcherName = Some(BlockingDispatcherName))

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

      converter ! RadioEventConverterActor.ConvertEvent(playerEvent)

      actorCreator.probePublisherActor.expectMessage(radioEvent)
    }
  }

}
