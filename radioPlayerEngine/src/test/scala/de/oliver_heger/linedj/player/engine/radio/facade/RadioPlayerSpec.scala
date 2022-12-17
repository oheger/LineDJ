/*
 * Copyright 2015-2022 The Developers Team.
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

import akka.actor.testkit.typed.scaladsl
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.{ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import akka.{actor => classics}
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.io.CloseRequest
import de.oliver_heger.linedj.player.engine.actors.PlayerFacadeActor.SourceActorCreator
import de.oliver_heger.linedj.player.engine.actors._
import de.oliver_heger.linedj.player.engine.facade.PlayerControl
import de.oliver_heger.linedj.player.engine.interval.IntervalQueries
import de.oliver_heger.linedj.player.engine.radio.actors.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.radio.actors.schedule.RadioSchedulerActor
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioSource}
import de.oliver_heger.linedj.player.engine.{ActorCreator, PlayerConfig}
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
    val helper = new RadioPlayerTestHelper
    val exclusions = Map(RadioSource("1") -> List(IntervalQueries.hours(1, 2)),
      RadioSource("2") -> List(IntervalQueries.hours(4, 5)))
    val ranking: RadioSource.Ranking = src => src.uri.length

    helper.player.initSourceExclusions(exclusions, ranking)
    helper.probeSchedulerActor.expectMsg(RadioSchedulerActor.RadioSourceData(exclusions,
      ranking))
  }

  it should "pass the event actor to the super class" in {
    val helper = new RadioPlayerTestHelper

    helper.player removeEventSink 20160709
    helper.probeEventActorOld.expectMsgType[EventManagerActorOld.RemoveSink]
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
    /** Test probe for the facade actor. */
    val probeFacadeActor: TestProbe = TestProbe()

    /** Test probe for the line writer actor. */
    val probeLineWriterActor: TestProbe = TestProbe()

    /** Test probe for the scheduler actor. */
    val probeSchedulerActor: TestProbe = TestProbe()

    /** Test probe for the legacy event manager actor. */
    val probeEventActorOld: TestProbe = TestProbe()

    /** Test probe for the event manager actor. */
    val probeEventActor: scaladsl.TestProbe[EventManagerActor.EventManagerCommand[RadioEvent]] =
      testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the event publisher actor. */
    val probePublisherActor: scaladsl.TestProbe[RadioEvent] = testKit.createTestProbe[RadioEvent]()

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
    private def createActorCreator(): ActorCreator = {
      val mockEventManagerBehavior =
        Behaviors.receiveMessagePartial[EventManagerActor.EventManagerCommand[RadioEvent]] {
          case EventManagerActor.GetPublisher(client) =>
            client ! EventManagerActor.PublisherReference(probePublisherActor.ref)
            Behaviors.same
        }

      new ActorCreator {
        override def createActor[T](behavior: Behavior[T], name: String, optStopCommand: Option[T]): ActorRef[T] =
          name match {
            case "radioEventManagerActor" =>
              optStopCommand should be(Some(EventManagerActor.Stop[RadioEvent]()))
              testKit.spawn(Behaviors.monitor(probeEventActor.ref, mockEventManagerBehavior)).asInstanceOf[ActorRef[T]]

            case _ => testKit.createTestProbe[T]().ref // helper actors
          }

        override def createActor(props: Props, name: String): classics.ActorRef =
          name match {
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
              props.args.head should be(probeEventActorOld.ref)
              probeSchedulerActor.ref

            case "radioEventManagerActorOld" =>
              props.actorClass() should be(classOf[EventManagerActorOld])
              props.args should have size 0
              probeEventActorOld.ref

            case "radioPlayerFacadeActor" =>
              classOf[PlayerFacadeActor] isAssignableFrom props.actorClass() shouldBe true
              classOf[ChildActorFactory] isAssignableFrom props.actorClass() shouldBe true
              props.args should have size 4
              props.args.take(3) should contain theSameElementsInOrderAs List(config, probeEventActorOld.ref,
                probeLineWriterActor.ref)
              val creator = props.args(3).asInstanceOf[SourceActorCreator]
              checkSourceActorCreator(creator)
              probeFacadeActor.ref
          }
      }
    }

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
        props.args should be(List(config, probeEventActorOld.ref))
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
      PlayerConfig(mediaManagerActor = null, actorCreator = createActorCreator(),
        blockingDispatcherName = Some(BlockingDispatcherName))
  }

}
