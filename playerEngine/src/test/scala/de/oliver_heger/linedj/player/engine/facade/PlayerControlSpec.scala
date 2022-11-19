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

package de.oliver_heger.linedj.player.engine.facade

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, LineWriterActor, PlaybackActor, PlayerFacadeActor}
import de.oliver_heger.linedj.player.engine.{PlaybackContextFactory, PlayerConfig, PlayerEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Failure

/**
  * Test class for ''PlayerControl''.
  */
class PlayerControlSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("PlayerControlSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A PlayerControl" should "allow adding a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player addPlaybackContextFactory factory
    helper.probePlayerFacadeActor.expectMsg(PlaybackActor.AddPlaybackContextFactory(factory))
  }

  it should "allow removing a playback context factory" in {
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player removePlaybackContextFactory factory
    helper.probePlayerFacadeActor.expectMsg(PlaybackActor.RemovePlaybackContextFactory(factory))
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
    val config = PlayerConfig(mediaManagerActor = null, actorCreator = (_, _) => null)

    PlayerControl createLineWriterActorProps config should be(Props[LineWriterActor]())
  }

  it should "support adding event listeners" in {
    def createSink(): Sink[PlayerEvent, Any] =
      Sink.foreach[PlayerEvent](println)

    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val sink1 = createSink()
    val sink2 = createSink()

    val regID1 = player registerEventSink sink1
    val regID2 = player registerEventSink sink2
    regID1 should not be regID2
    helper.probeEventManagerActor.expectMsg(EventManagerActor.RegisterSink(regID1, sink1))
    helper.probeEventManagerActor.expectMsg(EventManagerActor.RegisterSink(regID2, sink2))
  }

  it should "support removing event listeners" in {
    val SinkID = 20160708
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player removeEventSink SinkID
    helper.probeEventManagerActor.expectMsg(EventManagerActor.RemoveSink(SinkID))
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
  private def createCloseTestActors(count: Int, closeCounter: AtomicInteger): IndexedSeq[ActorRef] =
    (1 to count) map (_ => system.actorOf(Props(new Actor {
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
    * A test helper class managing some dependencies of the test class. It also
    * provides a concrete implementation of the trait under test.
    */
  private class PlayerControlTestHelper {
    /** The test event manager actor. */
    val probeEventManagerActor: TestProbe = TestProbe()

    /** Test probe for the player facade actor. */
    val probePlayerFacadeActor: TestProbe = TestProbe()

    /**
      * Creates a test instance of ''PlayerControl''.
      *
      * @return the test instance
      */
    def createPlayerControl(): PlayerControlImpl =
      new PlayerControlImpl(probeEventManagerActor.ref, probePlayerFacadeActor.ref)

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
  * @param eventManagerActor the event manager actor
  * @param playerFacadeActor the player facade actor
  */
private class PlayerControlImpl(override val eventManagerActor: ActorRef,
                                override val playerFacadeActor: ActorRef)
  extends PlayerControl {
  override def closeActors(actors: Seq[ActorRef])(implicit ec: ExecutionContext, timeout:
  Timeout): Future[Seq[CloseAck]] = super.closeActors(actors)

  override def close()(implicit ec: ExecutionContext, timeout: Timeout): Future[scala
  .Seq[CloseAck]] = {
    throw new UnsupportedOperationException("Unexpected invocation!")
  }
}
