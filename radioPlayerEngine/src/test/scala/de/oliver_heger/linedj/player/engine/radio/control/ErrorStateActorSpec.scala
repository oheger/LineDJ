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

import akka.actor.Actor
import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe => TypedTestProbe}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.ByteString
import akka.{actor => classic}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.{LineWriterActor, PlaybackActor, PlaybackContextFactoryActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioPlaybackContextCreationFailedEvent, RadioPlaybackErrorEvent, RadioPlaybackProgressEvent, RadioSource, RadioSourceChangedEvent, RadioSourceErrorEvent, RadioSourceReplacementEndEvent, RadioSourceReplacementStartEvent}
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.{ActorCreator, AudioSource, AudioSourceFinishedEvent, AudioSourceStartedEvent, PlaybackContextCreationFailedEvent, PlaybackErrorEvent, PlaybackProgressEvent, PlayerConfig, PlayerEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._

object ErrorStateActorSpec {
  /** A prefix for actor names. */
  private val ActorNamePrefix = "ErrorCheck_"

  /** A test player configuration. */
  private val TestPlayerConfig = PlayerConfig(actorCreator = null, mediaManagerActor = null)

  /** A radio source used by tests */
  private val TestRadioSource = RadioSource("http://radio.example.org/music.mp3")
}

/**
  * Test class for the [[ErrorStateActor]] module.
  */
class ErrorStateActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers {
  def this() = this(classic.ActorSystem("ErrorStateActorSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import ErrorStateActorSpec._

  /**
    * Tests whether the given actor has been stopped.
    *
    * @param actor the actor to check
    * @tparam T the message type of this actor
    */
  private def checkStopped[T](actor: ActorRef[T]): Unit = {
    val probe = testKit.createDeadLetterProbe()
    probe.expectTerminated(actor)
  }

  "playbackActorsFactory" should "create correct playback actors" in {
    val creator = new ActorCreatorForPlaybackActors

    val result = creator.callPlaybackFactory(ErrorStateActor.playbackActorsFactory)

    result.playActor should be(creator.probePlayActor.ref)
    result.sourceActor should be(creator.probeSourceActor.ref)
  }

  it should "create a line writer actor responding to a write command" in {
    val probe = TestProbe()
    val creator = new ActorCreatorForPlaybackActors
    creator.callPlaybackFactory(ErrorStateActor.playbackActorsFactory)

    val writeCommand = LineWriterActor.WriteAudioData(line = null, data = ByteString("some data"),
      replyTo = probe.ref)
    creator.lineWriterActor ! writeCommand

    val writtenMsg = probe.expectMsgType[LineWriterActor.AudioDataWritten]
    writtenMsg.duration.toMillis should be >= 1000L
    writtenMsg.chunkLength should be >= 1024
  }

  it should "create a line writer actor responding to a drain command" in {
    val probe = TestProbe()
    val creator = new ActorCreatorForPlaybackActors
    creator.callPlaybackFactory(ErrorStateActor.playbackActorsFactory)

    creator.lineWriterActor ! LineWriterActor.DrainLine(null, probe.ref)

    probe.expectMsg(LineWriterActor.LineDrained)
  }

  "Check playback actor" should "trigger the check of the current radio source" in {
    val factory = new PlaybackActorsFactoryTestImpl

    factory.createCheckPlaybackActor()

    factory.probeSourceActor.expectMsg(TestRadioSource)
    factory.probePlayActor.expectMsg(PlaybackActor.StartPlayback)
  }

  it should "handle a timeout command" in {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()

    checkActor ! ErrorStateActor.CheckTimeout

    factory.expectCheckResult(checkActor, success = false)
  }

  it should "trigger only a single close operation and wait for its completion" in {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()
    val deadLetters = testKit.createDeadLetterProbe()
    checkActor ! ErrorStateActor.CheckTimeout
    factory.expectCloseRequest(factory.probeSourceActor, reply = false)

    checkActor ! ErrorStateActor.CheckTimeout

    factory.probeSourceActor.expectNoMessage(250.millis)
    deadLetters.expectNoMessage(250.millis)
  }

  it should "stop itself if the source actor dies" in {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()

    system.stop(factory.probeSourceActor.ref)

    factory.expectCloseRequest(factory.probePlayActor)
      .expectNoSuccess()
    checkStopped(checkActor)
  }

  it should "stop itself if the play actor dies" in {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()

    system.stop(factory.probePlayActor.ref)

    factory.expectCloseRequest(factory.probeSourceActor)
      .expectNoSuccess()
    checkStopped(checkActor)
  }

  it should "report a successful check on receiving a playback progress event" in {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()

    factory.playerEventActor ! PlaybackProgressEvent(1024L, 1.second, AudioSource("test", 10000, 0, 0))

    factory.expectCheckResult(checkActor, success = true)
  }

  it should "ignore irrelevant player events" in {
    val audioSource = AudioSource.infinite(TestRadioSource.uri)
    val events = List(AudioSourceStartedEvent(audioSource), AudioSourceFinishedEvent(audioSource))
    val factory = new PlaybackActorsFactoryTestImpl
    factory.createCheckPlaybackActor()

    events foreach factory.playerEventActor.!

    factory.probePlayActor.expectMsgType[AnyRef] // The original start playback message
    factory.probePlayActor.expectNoMessage(200.millis)
  }

  it should "ignore irrelevant radio events" in {
    val events = List(RadioSourceChangedEvent(TestRadioSource),
      RadioSourceReplacementStartEvent(TestRadioSource, TestRadioSource),
      RadioSourceReplacementEndEvent(TestRadioSource),
      RadioPlaybackProgressEvent(TestRadioSource, 1024L, 1.second))
    val factory = new PlaybackActorsFactoryTestImpl
    factory.createCheckPlaybackActor()

    events foreach factory.radioEventActor.!

    factory.probePlayActor.expectMsgType[AnyRef] // The original start playback message
    factory.probePlayActor.expectNoMessage(200.millis)
  }

  /**
    * Checks whether an error event sent to the player event actor is correctly
    * detected.
    *
    * @param event the event
    */
  private def checkErrorPlayerEvent(event: PlayerEvent): Unit = {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()

    factory.playerEventActor ! event

    factory.expectCheckResult(checkActor, success = false)
  }

  it should "handle a PlaybackContextCreationFailedEvent event" in {
    checkErrorPlayerEvent(PlaybackContextCreationFailedEvent(AudioSource.infinite(TestRadioSource.uri)))
  }

  it should "handle a PlaybackErrorEvent event" in {
    checkErrorPlayerEvent(PlaybackErrorEvent(AudioSource.infinite(TestRadioSource.uri)))
  }

  /**
    * Checks whether an error event sent to the radio event actor is correctly
    * detected.
    *
    * @param event the event
    */
  private def checkErrorRadioEvent(event: RadioEvent): Unit = {
    val factory = new PlaybackActorsFactoryTestImpl
    val checkActor = factory.createCheckPlaybackActor()

    factory.radioEventActor ! event

    factory.expectCheckResult(checkActor, success = false)
  }

  it should "handle a RadioSourceErrorEvent" in {
    checkErrorRadioEvent(RadioSourceErrorEvent(TestRadioSource))
  }

  it should "handle a RadioPlaybackContextCreationFailedEvent" in {
    checkErrorRadioEvent(RadioPlaybackContextCreationFailedEvent(TestRadioSource))
  }

  it should "handle a RadioPlaybackErrorEvent" in {
    checkErrorRadioEvent(RadioPlaybackErrorEvent(TestRadioSource))
  }

  "Check scheduler actor" should "schedule check commands for radio sources" in {
    val helper = new CheckSchedulerTestHelper

    val probe = helper.handleSchedule()

    probe.expectMessage(helper.runCheckCommand)
  }

  it should "only trigger one check at a given time" in {
    val helper = new CheckSchedulerTestHelper
    helper.handleSchedule()

    val probe = helper.handleSchedule()

    probe.expectNoMessage(200.millis)
  }

  it should "continue with the next pending check if the current one is rescheduled" in {
    val helper = new CheckSchedulerTestHelper
    val probe1 = helper.handleSchedule()
    val probe2 = helper.handleSchedule()
    val probe3 = helper.handleSchedule()

    helper.send(ErrorStateActor.AddScheduledCheck(probe1.ref, 3.minutes))

    probe2.expectMessage(helper.runCheckCommand)
    helper.send(ErrorStateActor.AddScheduledCheck(probe2.ref, 20.seconds))

    probe3.expectMessage(helper.runCheckCommand)
  }

  it should "reset the current check when it is complete" in {
    val helper = new CheckSchedulerTestHelper
    val probe1 = helper.handleSchedule()
    helper.send(ErrorStateActor.AddScheduledCheck(probe1.ref, 10.seconds))
      .expectScheduledInvocation(10.seconds)

    val probe2 = helper.handleSchedule()

    probe2.expectMessage(helper.runCheckCommand)
  }

  it should "handle the termination of the current check actor" in {
    val helper = new CheckSchedulerTestHelper
    val probe1 = helper.handleSchedule()

    probe1.stop()

    val probe2 = helper.handleSchedule()
    probe2.expectMessage(helper.runCheckCommand)
  }

  /**
    * A test implementation of [[ActorCreator]] that checks whether correct
    * parameters are provided when creating the expected actors.
    */
  private class ActorCreatorForPlaybackActors extends ActorCreator {
    /** The test probe for the source actor. */
    val probeSourceActor: TestProbe = TestProbe()

    /** The test probe for the playback actor. */
    val probePlayActor: TestProbe = TestProbe()

    /** Test probe for the player event actor. */
    private val probePlayerEventActor: TypedTestProbe[PlayerEvent] = testKit.createTestProbe[PlayerEvent]()

    /** Test probe for the radio event actor. */
    private val probeRadioEventActor: TypedTestProbe[RadioEvent] = testKit.createTestProbe[RadioEvent]()

    /** Test probe for the playback context factory actor. */
    private val probeFactoryActor: TypedTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand] =
      testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** Stores the line writer actor created via the factory. */
    var lineWriterActor: ActorRef[LineWriterActor.LineWriterCommand] = _

    /**
      * Invokes the given factory for playback actors with the test data
      * managed by this instance.
      *
      * @param factory the factory to invoke
      * @return the result produced by the factory
      */
    def callPlaybackFactory(factory: ErrorStateActor.PlaybackActorsFactory):
    ErrorStateActor.PlaybackActorsFactoryResult =
      factory.createPlaybackActors(ActorNamePrefix, probePlayerEventActor.ref, probeRadioEventActor.ref,
        probeFactoryActor.ref, TestPlayerConfig, this)

    override def createActor[T](behavior: Behavior[T],
                                name: String,
                                optStopCommand: Option[T],
                                props: Props): ActorRef[T] = {
      name should be(ActorNamePrefix + "LineWriter")
      optStopCommand shouldBe empty
      lineWriterActor = testKit.spawn(behavior).asInstanceOf[ActorRef[LineWriterActor.LineWriterCommand]]
      lineWriterActor.asInstanceOf[ActorRef[T]]
    }

    override def createActor(props: classic.Props, name: String): classic.ActorRef = {
      name match {
        case s"${ActorNamePrefix}SourceActor" =>
          val expProps = RadioDataSourceActor(TestPlayerConfig, probeRadioEventActor.ref)
          props should be(expProps)
          probeSourceActor.ref

        case s"${ActorNamePrefix}PlaybackActor" =>
          props.args should have size 5
          val expProps = PlaybackActor(TestPlayerConfig, probeSourceActor.ref, lineWriterActor,
            probePlayerEventActor.ref, probeFactoryActor.ref)
          props should be(expProps)
          probePlayActor.ref
      }
    }
  }

  /**
    * A test implementation of the factory for playback actors. This class
    * manages test probes for the playback actors to check the interaction with
    * them.
    */
  private class PlaybackActorsFactoryTestImpl extends ErrorStateActor.PlaybackActorsFactory {
    /** Test probe for the radio data source actor. */
    val probeSourceActor: TestProbe = TestProbe()

    /** Test probe for the playback actor. */
    val probePlayActor: TestProbe = TestProbe()

    /** Test probe for the playback context factory actor. */
    private val probeFactoryActor = testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /**
      * Test probe for the actor receiving the result of a radio source check.
      */
    private val probeReceiverActor = testKit.createTestProbe[ErrorStateActor.RadioSourceCheckSuccessful]()

    /** Stores the actor for receiving player events. */
    private val refPlayerEventActor = new AtomicReference[ActorRef[PlayerEvent]]

    /** Stores the actor for receiving radio events. */
    private val refRadioEventActor = new AtomicReference[ActorRef[RadioEvent]]

    /**
      * Creates a check playback actor instance that is configured with test
      * settings and this factory.
      *
      * @return the check actor instance
      */
    def createCheckPlaybackActor(): ActorRef[ErrorStateActor.CheckPlaybackCommand] = {
      val behavior = ErrorStateActor.checkPlaybackBehavior(probeReceiverActor.ref, TestRadioSource, ActorNamePrefix,
        probeFactoryActor.ref, TestPlayerConfig, this)
      testKit.spawn(behavior)
    }

    /**
      * Expects that a success message was sent to the receiver actor.
      *
      * @return this factory
      */
    def expectSuccess(): PlaybackActorsFactoryTestImpl = {
      probeReceiverActor.expectMessage(ErrorStateActor.RadioSourceCheckSuccessful())
      this
    }

    /**
      * Expects that no success message was sent to the receiver actor.
      *
      * @return this factory
      */
    def expectNoSuccess(): PlaybackActorsFactoryTestImpl = {
      probeReceiverActor.expectNoMessage(250.millis)
      this
    }

    /**
      * Expects that the given probe receives a close request. Optionally, the
      * request is answered with an ACK.
      *
      * @param probe the test probe
      * @param reply flag whether to reply with an ACK
      * @return this factory
      */
    def expectCloseRequest(probe: TestProbe, reply: Boolean = true): PlaybackActorsFactoryTestImpl = {
      probe.fishForSpecificMessage() {
        case CloseRequest => CloseRequest
      }
      if (reply) {
        probe.reply(CloseAck(probe.ref))
      }
      this
    }

    /**
      * Expects that the check actor triggers a close operation and stops
      * itself with a result determined by the success flag.
      *
      * @param checkActor the check actor
      * @param success    the success flag
      */
    def expectCheckResult(checkActor: ActorRef[ErrorStateActor.CheckPlaybackCommand], success: Boolean): Unit = {
      expectCloseRequest(probeSourceActor)
      expectCloseRequest(probePlayActor)
      if (success) expectSuccess() else expectNoSuccess()
      checkStopped(checkActor)
    }

    /**
      * Returns the actor for player events passed to this factory.
      *
      * @return the actor for receiving player events
      */
    def playerEventActor: ActorRef[PlayerEvent] = fetchFactoryParameter(refPlayerEventActor)

    /**
      * Returns the actor for radio events passed to this factory.
      *
      * @return the actor for receiving radio events
      */
    def radioEventActor: ActorRef[RadioEvent] = fetchFactoryParameter(refRadioEventActor)

    override def createPlaybackActors(namePrefix: String,
                                      playerEventActor: ActorRef[PlayerEvent],
                                      radioEventActor: ActorRef[RadioEvent],
                                      factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
                                      config: PlayerConfig,
                                      creator: ActorCreator): ErrorStateActor.PlaybackActorsFactoryResult = {
      namePrefix should be(ActorNamePrefix)
      config should be(TestPlayerConfig)
      refPlayerEventActor.set(playerEventActor)
      refRadioEventActor.set(radioEventActor)
      testActorCreatorTyped(creator)
      testActorCreatorClassic(creator)

      ErrorStateActor.PlaybackActorsFactoryResult(sourceActor = probeSourceActor.ref,
        playActor = probePlayActor.ref)
    }

    /**
      * Tests whether the [[ActorCreator]] passed to this factory can be used
      * to create a typed actor.
      *
      * @param creator the creator to test
      */
    private def testActorCreatorTyped(creator: ActorCreator): Unit = {
      val Message = new Object
      val ActorName = ActorNamePrefix + "typedTestActor"
      val ref = new AtomicReference[AnyRef]

      val behavior = Behaviors.receiveMessagePartial[AnyRef] {
        case msg =>
          ref.set(msg)
          Behaviors.same
      }
      val testActor = creator.createActor(behavior, ActorName, None)

      testActor.path.name should endWith(ActorName)
      testActor ! Message
      awaitCond(ref.get() == Message)
    }

    /**
      * Tests whether the [[ActorCreator]] passed to this factory can be used
      * to create a classic actor.
      *
      * @param creator the creator to test
      */
    private def testActorCreatorClassic(creator: ActorCreator): Unit = {
      val Message = new Object
      val ActorName = ActorNamePrefix + "classicTestActor"
      val ref = new AtomicReference[Any]

      val props = classic.Props(new Actor {
        override def receive: Receive = {
          case msg => ref.set(msg)
        }
      })
      val testActor = creator.createActor(props, ActorName)

      testActor.path.name should endWith(ActorName)
      testActor ! Message
      awaitCond(ref.get() == Message)
    }

    /**
      * Returns one of the parameters passed to the factory method. Since the
      * factory is called asynchronously, we might have to wait until the value
      * becomes available.
      *
      * @param ref the reference storing the value
      * @tparam T the type of the parameter
      * @return the parameter value
      */
    private def fetchFactoryParameter[T](ref: AtomicReference[T]): T = {
      awaitCond(ref.get() != null)
      ref.get()
    }
  }

  /**
    * A test helper class for tests of the check scheduler actor.
    */
  private class CheckSchedulerTestHelper {
    /** Test probe for the scheduled invocation actor. */
    private val probeScheduler = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** The check scheduler actor to be tested. */
    private val checkScheduler = testKit.spawn(ErrorStateActor.checkSchedulerBehavior(probeScheduler.ref))

    /**
      * Sends a command to the scheduler actor under test.
      *
      * @param command the command to be sent
      * @return this test helper
      */
    def send(command: ErrorStateActor.ScheduleCheckCommand): CheckSchedulerTestHelper = {
      checkScheduler ! command
      this
    }

    /**
      * Expects that a scheduled invocation command was issued with the given
      * properties.
      *
      * @param delay the delay
      * @return the invocation
      */
    def expectScheduledInvocation(delay: FiniteDuration): ScheduledInvocationActor.TypedActorInvocation = {
      val scheduleMsg = probeScheduler.expectMessageType[ScheduledInvocationActor.TypedInvocationCommand]
      scheduleMsg.delay should be(delay)
      scheduleMsg.invocation.receiver should be(checkScheduler)
      scheduleMsg.invocation
    }

    /**
      * Simulates a complete schedule for a newly created test probe. A command
      * to add a schedule for this probe is sent to the test actor, as well as
      * the answer from the scheduled invocation actor. Returns the test probe.
      *
      * @return the test probe simulating a check actor
      */
    def handleSchedule(): TypedTestProbe[ErrorStateActor.CheckRadioSourceCommand] = {
      val delay = 90.seconds
      val probe = testKit.createTestProbe[ErrorStateActor.CheckRadioSourceCommand]()
      send(ErrorStateActor.AddScheduledCheck(probe.ref, delay))

      val invocation = expectScheduledInvocation(delay)
      invocation.receiver ! invocation.message
      probe
    }

    /**
      * Returns the command to run a check on the current radio source as
      * expected by the actor under test.
      *
      * @return the expected command to check the current source
      */
    def runCheckCommand: ErrorStateActor.RunRadioSourceCheck =
      ErrorStateActor.RunRadioSourceCheck(probeScheduler.ref)
  }
}
