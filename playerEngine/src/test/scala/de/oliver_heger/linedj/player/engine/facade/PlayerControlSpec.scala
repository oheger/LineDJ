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

package de.oliver_heger.linedj.player.engine.facade

import akka.actor.testkit.typed.scaladsl
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.actor.{Actor, ActorSystem}
import akka.pattern.AskTimeoutException
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import akka.{actor => classic}
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, LineWriterActor, PlaybackActor, PlaybackContextFactoryActor, PlayerFacadeActor}
import de.oliver_heger.linedj.player.engine._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Failure

/**
  * Test class for ''PlayerControl''.
  */
class PlayerControlSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper {
  def this() = this(ActorSystem("PlayerControlSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
  }

  "A PlayerControl" should "allow adding a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player addPlaybackContextFactory factory
    helper.probeFactoryActor.expectMessage(PlaybackContextFactoryActor.AddPlaybackContextFactory(factory))
  }

  it should "allow removing a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player removePlaybackContextFactory factory
    helper.probeFactoryActor.expectMessage(PlaybackContextFactoryActor.RemovePlaybackContextFactory(factory))
  }

  it should "allow starting playback" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.startPlayback()
    helper.expectPlaybackInvocation(PlaybackActor.StartPlayback)
  }

  it should "allow starting playback with a delay" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val Delay = 1.minute

    player.startPlayback(Delay)
    helper.expectPlaybackInvocation(PlaybackActor.StartPlayback, Delay)
  }

  it should "allow stopping playback" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.stopPlayback()
    helper.expectPlaybackInvocation(PlaybackActor.StopPlayback)
  }

  it should "allow stopping playback with a delay" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val Delay = 30.seconds

    player.stopPlayback(Delay)
    helper.expectPlaybackInvocation(PlaybackActor.StopPlayback, Delay)
  }

  it should "allow skipping the current source" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.skipCurrentSource()
    helper.expectPlaybackInvocation(PlaybackActor.SkipSource)
  }

  it should "create Props for the line writer when no blocking dispatcher is defined" in {
    val config = PlayerConfig(mediaManagerActor = null, actorCreator = null)

    PlayerControl createLineWriterActorProps config should be(classic.Props[LineWriterActor]())
  }

  it should "support adding event listeners" in {
    val helper = new PlayerControlTestHelper
    val listener = testKit.createTestProbe[PlayerEvent]()
    val player = helper.createPlayerControl()

    player addEventListener listener.ref

    helper.probeEventManagerActor.expectMessage(EventManagerActor.RegisterListener(listener.ref))
  }

  it should "support removing event listeners" in {
    val helper = new PlayerControlTestHelper
    val listener = testKit.createTestProbe[PlayerEvent]()
    val player = helper.createPlayerControl()

    player removeEventListener listener.ref

    helper.probeEventManagerActor.expectMessage(EventManagerActor.RemoveListener(listener.ref))
  }

  it should "allow resetting the engine" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.reset()
    helper.probePlayerFacadeActor.expectMsg(PlayerFacadeActor.ResetEngine)
  }

  /**
    * Creates a list of test actors that just react on a close request by
    * sending the corresponding ACK.
    *
    * @param count        the number of test actors to create
    * @param closeCounter a counter for recording close requests
    * @return the list with test actors
    */
  private def createCloseTestActors(count: Int, closeCounter: AtomicInteger): IndexedSeq[classic.ActorRef] =
    (1 to count) map (_ => system.actorOf(classic.Props(new Actor {
      override def receive: Receive = {
        case CloseRequest =>
          closeCounter.incrementAndGet()
          sender() ! CloseAck(self)
      }
    })))

  it should "provide a method to close dependent actors" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val counter = new AtomicInteger

    val probes = createCloseTestActors(3, counter)
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(1.second)
    val handle = player.closeActors(probes)
    helper.probePlayerFacadeActor.expectMsg(CloseRequest)
    helper.probePlayerFacadeActor.reply(CloseAck(helper.probePlayerFacadeActor.ref))
    val result = Await.result(handle, 1.second)
    counter.get() should be(probes.size)
    val closedActors = result map (_.actor)
    closedActors should contain allElementsOf probes
    closedActors should contain(helper.probePlayerFacadeActor.ref)
    closedActors should have size probes.size + 1
  }

  it should "do correct timeout handling in its closeActors() method" in {
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val latch = new CountDownLatch(1)

    val probes = createCloseTestActors(2, new AtomicInteger).toList
    val timeoutProbe = TestProbe()
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(200.milliseconds)
    player.closeActors(timeoutProbe.ref :: probes).onComplete {
      case Failure(_: AskTimeoutException) => latch.countDown()
      case r => fail("Unexpected result: " + r)
    }
    latch.await(1, TimeUnit.SECONDS) shouldBe true
  }

  /**
    * Creates an [[ActorCreator]] object that can be used for tests of the
    * creation of the event manager actor.
    *
    * @param actorName the expected name of the event manager actor
    * @return the [[ActorCreator]]
    */
  private def createActorCreatorForEventManager(actorName: String): ActorCreator =
    new ActorCreator {
      override def createActor[T](behavior: Behavior[T], name: String, optStopCommand: Option[T], props: Props):
      ActorRef[T] = {
        if (name == actorName) {
          optStopCommand should be(Some(EventManagerActor.Stop[PlayerEvent]()))
        }
        props should be(Props.empty)
        testKit.spawn(behavior)
      }

      override def createActor(props: classic.Props, name: String): classic.ActorRef = {
        name should be(actorName + "Old")
        system.actorOf(props)
      }
    }

  it should "create an event publisher actor" in {
    val ActorName = "MyTestEventManagerWithPublishing"
    val event = AudioSourceStartedEvent(AudioSource("testPublish", 16384, 0, 0))
    val creator = createActorCreatorForEventManager(ActorName)

    implicit val ec: ExecutionContext = system.dispatcher
    val (eventManager, eventPublisher) =
      futureResult(PlayerControl.createEventManagerActorWithPublisher[PlayerEvent](creator, ActorName))
    val probeListener = testKit.createTestProbe[PlayerEvent]()
    eventManager ! EventManagerActor.RegisterListener(probeListener.ref)

    eventManager ! EventManagerActor.Publish(event)
    probeListener.expectMessage(event)
    eventPublisher ! event
    probeListener.expectMessage(event)
  }

  /**
    * A test helper class managing some dependencies of the test class. It also
    * provides a concrete implementation of the trait under test.
    */
  private class PlayerControlTestHelper {
    /** Test test event manager actor. */
    val probeEventManagerActor: scaladsl.TestProbe[EventManagerActor.EventManagerCommand[PlayerEvent]] =
      testKit.createTestProbe[EventManagerActor.EventManagerCommand[PlayerEvent]]()

    /** Test test playback context factory manager actor. */
    val probeFactoryActor: scaladsl.TestProbe[PlaybackContextFactoryActor.PlaybackContextCommand] =
      testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** Test probe for the player facade actor. */
    val probePlayerFacadeActor: TestProbe = TestProbe()

    /**
      * Creates a test instance of ''PlayerControl''.
      *
      * @return the test instance
      */
    def createPlayerControl(): PlayerControlImpl =
      new PlayerControlImpl(playerFacadeActor = probePlayerFacadeActor.ref,
        eventManagerActor = probeEventManagerActor.ref,
        playbackContextFactoryActor = probeFactoryActor.ref)

    /**
      * Expects an invocation of the player facade actor with the parameters
      * specified.
      *
      * @param msg    the actual message
      * @param target the target referencing the receiver
      * @param delay  the delay
      * @return this test helper
      */
    def expectFacadeActorInvocation(msg: Any, target: PlayerFacadeActor.TargetActor,
                                    delay: FiniteDuration = PlayerControl.NoDelay): PlayerControlTestHelper = {
      val expMsg = PlayerFacadeActor.Dispatch(msg, target, delay)
      probePlayerFacadeActor.expectMsg(expMsg)
      this
    }

    /**
      * Expects an invocation of the playback actor.
      *
      * @param msg   the message
      * @param delay the delay
      * @return this test helper
      */
    def expectPlaybackInvocation(msg: Any, delay: FiniteDuration = PlayerControl.NoDelay):
    PlayerControlTestHelper =
      expectFacadeActorInvocation(msg, PlayerFacadeActor.TargetPlaybackActor, delay)
  }

}

/**
  * A test implementation of the trait which wraps the specified actor.
  *
  * @param playerFacadeActor the player facade actor
  * @param eventManagerActor the event manager actor
  * @param playbackContextFactoryActor the factory actor
  */
private class PlayerControlImpl(override val playerFacadeActor: classic.ActorRef,
                                override val eventManagerActor:
                                ActorRef[EventManagerActor.EventManagerCommand[PlayerEvent]],
                                override val playbackContextFactoryActor:
                                ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand])
  extends PlayerControl[PlayerEvent] {
  override def closeActors(actors: Seq[classic.ActorRef])(implicit ec: ExecutionContext, timeout:
  Timeout): Future[Seq[CloseAck]] = super.closeActors(actors)

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[scala
  .Seq[CloseAck]] = {
    throw new UnsupportedOperationException("Unexpected invocation!")
  }
}
