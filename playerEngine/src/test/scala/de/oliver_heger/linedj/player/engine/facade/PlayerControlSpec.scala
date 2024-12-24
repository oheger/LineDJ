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

package de.oliver_heger.linedj.player.engine.facade

import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.*
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.facade.PlayerControlSpec.{PlaybackCommand, PlayerControlImpl, StartPlayback, StopPlayback}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.pattern.AskTimeoutException
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.Timeout
import org.apache.pekko.actor as classic
import org.mockito.Mockito.verify
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object PlayerControlSpec:
  /**
    * Base command trait for a synthetic actor controlling the playback state.
    * This is used by the test player control implementation to test the
    * functions to start or stop playback.
    */
  private sealed trait PlaybackCommand

  /**
    * A command to start playback.
    */
  private case object StartPlayback extends PlaybackCommand

  /**
    * A command to stop playback.
    */
  private case object StopPlayback extends PlaybackCommand

  /**
    * A test implementation of the trait used for testing.
    *
    * @param playerFacadeActor           the player facade actor
    * @param eventManagerActor           the event manager actor
    * @param playbackContextFactoryActor the factory actor
    * @param scheduledInvocationActor    the scheduler actor
    * @param playbackActor               the actor controlling playback
    * @param dynamicAudioStreamFactory   the factory for audio streams
    */
  private class PlayerControlImpl(override val playerFacadeActor: classic.ActorRef,
                                  override val eventManagerActor:
                                  ActorRef[EventManagerActor.EventManagerCommand[PlayerEvent]],
                                  override val playbackContextFactoryActor:
                                  ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                                  override val scheduledInvocationActor:
                                  ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
                                  playbackActor: ActorRef[PlaybackCommand],
                                  override val dynamicAudioStreamFactory: DynamicAudioStreamFactory)
    extends PlayerControl[PlayerEvent]:

    override protected val startPlaybackInvocation: ScheduledInvocationActor.ActorInvocation =
      ScheduledInvocationActor.typedInvocation(playbackActor, StartPlayback)

    override protected val stopPlaybackInvocation: ScheduledInvocationActor.ActorInvocation =
      ScheduledInvocationActor.typedInvocation(playbackActor, StopPlayback)

/**
  * Test class for ''PlayerControl''.
  */
class PlayerControlSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper:
  def this() = this(ActorSystem("PlayerControlSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()

  "A PlayerControl" should "allow adding a playback context factory" in :
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player addPlaybackContextFactory factory
    helper.probeFactoryActor.expectMessage(PlaybackContextFactoryActor.AddPlaybackContextFactory(factory))

  it should "allow removing a playback context factory" in :
    val factory = mock[PlaybackContextFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player removePlaybackContextFactory factory
    helper.probeFactoryActor.expectMessage(PlaybackContextFactoryActor.RemovePlaybackContextFactory(factory))

  it should "allow adding an audio stream factory" in :
    val factory = mock[AudioStreamFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.addAudioStreamFactory(factory)

    verify(helper.dynamicStreamFactoryMock).addAudioStreamFactory(factory)

  it should "allow removing an audio stream factory" in :
    val factory = mock[AudioStreamFactory]
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.removeAudioStreamFactory(factory)

    verify(helper.dynamicStreamFactoryMock).removeAudioStreamFactory(factory)

  it should "allow starting playback directly" in :
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.startPlayback()

    helper.expectNoScheduledInvocation()
      .expectPlaybackCommand(StartPlayback)

  it should "allow starting playback with a delay" in :
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val Delay = 1.minute

    player.startPlayback(Delay)

    val invocation = helper.expectNoPlaybackCommand()
      .expectScheduledInvocation()
    invocation.delay should be(Delay)
    invocation.invocation.send()
    helper.expectPlaybackCommand(StartPlayback)

  it should "allow stopping playback directly" in :
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    player.stopPlayback()

    helper.expectNoScheduledInvocation()
      .expectPlaybackCommand(StopPlayback)

  it should "allow stopping playback with a delay" in :
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()
    val Delay = 30.seconds

    player.stopPlayback(Delay)

    val invocation = helper.expectNoPlaybackCommand()
      .expectScheduledInvocation()
    invocation.delay should be(Delay)
    invocation.invocation.send()
    helper.expectPlaybackCommand(StopPlayback)

  it should "create Props for the line writer when no blocking dispatcher is defined" in :
    val config = PlayerConfigSpec.TestPlayerConfig

    PlayerControl createLineWriterActorProps config should be(Props.empty)

  it should "support adding event listeners" in :
    val helper = new PlayerControlTestHelper
    val listener = testKit.createTestProbe[PlayerEvent]()
    val player = helper.createPlayerControl()

    player addEventListener listener.ref

    helper.probeEventManagerActor.expectMessage(EventManagerActor.RegisterListener(listener.ref))

  it should "support removing event listeners" in :
    val helper = new PlayerControlTestHelper
    val listener = testKit.createTestProbe[PlayerEvent]()
    val player = helper.createPlayerControl()

    player removeEventListener listener.ref

    helper.probeEventManagerActor.expectMessage(EventManagerActor.RemoveListener(listener.ref))

  it should "allow closing the player gracefully" in :
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(1.second)
    val handle = player.close()
    helper.probePlayerFacadeActor.expectMsg(CloseRequest)
    helper.probePlayerFacadeActor.reply(CloseAck(helper.probePlayerFacadeActor.ref))
    val result = futureResult(handle)

    val closedActors = result map (_.actor)
    closedActors should contain(helper.probePlayerFacadeActor.ref)
    closedActors should have size 1

  it should "do correct timeout handling in its closeActors() method" in :
    val helper = new PlayerControlTestHelper
    val player = helper.createPlayerControl()

    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(200.milliseconds)
    val result = player.close()

    expectFailedFuture[AskTimeoutException](result)

  /**
    * Creates an [[ActorCreator]] object that can be used for tests of the
    * creation of the event manager actor.
    *
    * @param actorName the expected name of the event manager actor
    * @return the [[ActorCreator]]
    */
  private def createActorCreatorForEventManager(actorName: String): ActorCreator =
    new ActorCreator:
      override def createActor[T](behavior: Behavior[T], name: String, optStopCommand: Option[T], props: Props):
      ActorRef[T] =
        if name == actorName then
          optStopCommand should be(Some(EventManagerActor.Stop[PlayerEvent]()))
        props should be(Props.empty)
        testKit.spawn(behavior)

      override def createClassicActor(props: classic.Props,
                                      name: String,
                                      optStopCommand: Option[Any]): classic.ActorRef =
        name should be(actorName + "Old")
        optStopCommand shouldBe empty
        system.actorOf(props)

  it should "create an event publisher actor" in :
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

  /**
    * A test helper class managing some dependencies of the test class. It also
    * provides a concrete implementation of the trait under test.
    */
  private class PlayerControlTestHelper:
    /** Test probe for the event manager actor. */
    val probeEventManagerActor: scaladsl.TestProbe[EventManagerActor.EventManagerCommand[PlayerEvent]] =
      testKit.createTestProbe[EventManagerActor.EventManagerCommand[PlayerEvent]]()

    /** Test probe for the playback context factory manager actor. */
    val probeFactoryActor: scaladsl.TestProbe[PlaybackContextFactoryActor.PlaybackContextCommand] =
      testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** Test probe for the player facade actor. */
    val probePlayerFacadeActor: TestProbe = TestProbe()

    /** Mock for the dynamic audio stream factory. */
    val dynamicStreamFactoryMock: DynamicAudioStreamFactory = mock[DynamicAudioStreamFactory]

    /** Test probe for the scheduled invocation actor. */
    private val probeSchedulerActor: scaladsl.TestProbe[ScheduledInvocationActor.ScheduledInvocationCommand] =
      testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for receiving start/stop playback events. */
    private val probePlaybackActor = testKit.createTestProbe[PlaybackCommand]()

    /**
      * Creates a test instance of ''PlayerControl''.
      *
      * @return the test instance
      */
    def createPlayerControl(): PlayerControlImpl =
      new PlayerControlImpl(playerFacadeActor = probePlayerFacadeActor.ref,
        eventManagerActor = probeEventManagerActor.ref,
        playbackContextFactoryActor = probeFactoryActor.ref,
        scheduledInvocationActor = probeSchedulerActor.ref,
        playbackActor = probePlaybackActor.ref,
        dynamicAudioStreamFactory = dynamicStreamFactoryMock)

    /**
      * Expects a scheduled invocation command to be sent to the scheduler
      * actor.
      *
      * @return the command received by the scheduler actor
      */
    def expectScheduledInvocation(): ScheduledInvocationActor.ActorInvocationCommand =
      probeSchedulerActor.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]

    /**
      * Expects that no message is sent to the scheduler actor.
      *
      * @return this test helper
      */
    def expectNoScheduledInvocation(): PlayerControlTestHelper =
      probeSchedulerActor.expectNoMessage(200.millis)
      this

    /**
      * Expects that the given playback command was sent to the playback actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectPlaybackCommand(command: PlaybackCommand): PlayerControlTestHelper =
      probePlaybackActor.expectMessage(command)
      this

    /**
      * Expects that no message is sent to the playback actor.
      *
      * @return this test helper
      */
    def expectNoPlaybackCommand(): PlayerControlTestHelper =
      probePlaybackActor.expectNoMessage(200.millis)
      this

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
                                    delay: FiniteDuration = PlayerControl.NoDelay): PlayerControlTestHelper =
      val expMsg = PlayerFacadeActor.Dispatch(msg, target, delay)
      probePlayerFacadeActor.expectMsg(expMsg)
      this

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
