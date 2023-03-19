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
import com.github.cloudfiles.core.http.factory.Spawner
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, LineWriterActor, PlaybackActor, PlaybackContextFactoryActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.{RadioEvent, RadioPlaybackContextCreationFailedEvent, RadioPlaybackErrorEvent, RadioPlaybackProgressEvent, RadioPlayerConfig, RadioSource, RadioSourceChangedEvent, RadioSourceErrorEvent, RadioSourceReplacementEndEvent, RadioSourceReplacementStartEvent}
import de.oliver_heger.linedj.player.engine.radio.stream.RadioDataSourceActor
import de.oliver_heger.linedj.player.engine.{ActorCreator, AudioSource, AudioSourceFinishedEvent, AudioSourceStartedEvent, PlaybackContextCreationFailedEvent, PlaybackErrorEvent, PlaybackProgressEvent, PlayerConfig, PlayerEvent}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration._

object ErrorStateActorSpec {
  /** A prefix for actor names. */
  private val ActorNamePrefix = "ErrorCheck_"

  /** A test player configuration. */
  private val TestPlayerConfig = PlayerConfig(actorCreator = null, mediaManagerActor = null)

  /** A test radio player configuration. */
  private val TestRadioConfig = RadioPlayerConfig(playerConfig = TestPlayerConfig,
    retryFailedSource = 11.seconds,
    retryFailedSourceIncrement = 2.5,
    maxRetryFailedSource = 30.seconds,
    sourceCheckTimeout = 44.seconds)

  /** A radio source used by tests */
  private val TestRadioSource = RadioSource("http://radio.example.org/music.mp3")

  /** A counter for generating unique actor names. */
  private val counter = new AtomicInteger
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

  "Check radio source actor" should "execute a successful test" in {
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .expectPlaybackNamePrefix(helper.namePrefix + "_1_")
      .expectCheckPlaybackActorName(helper.namePrefix + "_1_check")
      .send(ErrorStateActor.RadioSourceCheckSuccessful())
      .stopCheckPlaybackActor()
      .expectActorStopped()
  }

  it should "use a default spawner" in {
    val helper = new CheckSourceTestHelper(provideSpawner = false)

    helper.triggerCheck()

    val probe = testKit.createDeadLetterProbe()
    helper.triggerCheck()
    probe.expectNoMessage(200.millis)
  }

  it should "reschedule a check if the current one failed" in {
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .stopCheckPlaybackActor()
      .expectSchedule(27500.millis)
      .triggerCheck()
      .expectPlaybackNamePrefix(helper.namePrefix + "_2_")
  }

  it should "take the maximum retry delay into account" in {
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .stopCheckPlaybackActor()
      .expectSchedule(27500.millis)
      .triggerCheck()
      .stopCheckPlaybackActor()
      .expectSchedule(TestRadioConfig.maxRetryFailedSource)
  }

  it should "handle timeouts correctly" in {
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .expectTimeoutSchedule()
      .sendCheckTimeout()
      .expectPlaybackActorTimeout()
  }

  it should "not stop itself before the current check actor is stopped" in {
    val helper = new CheckSourceTestHelper
    helper.triggerCheck()
      .expectTimeoutSchedule()
      .send(ErrorStateActor.RadioSourceCheckSuccessful())

    val probe = testKit.createDeadLetterProbe()
    helper.sendCheckTimeout()

    probe.expectNoMessage(200.millis)
  }

  it should "not send a timeout to the check playback actor if the check is already successful" in {
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .expectTimeoutSchedule()
      .send(ErrorStateActor.RadioSourceCheckSuccessful())
      .sendCheckTimeout()
      .expectNoPlaybackActorMessage()
  }

  it should "ignore a timeout for a previous check" in {
    val helper = new CheckSourceTestHelper
    helper.triggerCheck()
      .expectTimeoutSchedule()
      .stopCheckPlaybackActor()
      .triggerCheck()

    val probe = testKit.createDeadLetterProbe()
    helper.sendCheckTimeout()
      .expectNoPlaybackActorMessage()

    probe.expectNoMessage(100.millis)
  }

  "Error state actor" should "return an initial empty error state" in {
    val helper = new ErrorStateTestHelper

    val errorSources = helper.querySourcesInErrorState()

    errorSources.errorSources shouldBe empty
  }

  it should "handle a radio source playback error event" in {
    val errorSource = radioSource(7)
    val event = RadioPlaybackErrorEvent(errorSource)
    val helper = new ErrorStateTestHelper

    helper.testErrorEvent(event, errorSource)
  }

  it should "handle a radio source playback context creation failed event" in {
    val errorSource = radioSource(11)
    val event = RadioPlaybackContextCreationFailedEvent(errorSource)
    val helper = new ErrorStateTestHelper

    helper.testErrorEvent(event, errorSource)
  }

  it should "handle a radio source error event" in {
    val errorSource = radioSource(13)
    val event = RadioSourceErrorEvent(errorSource)
    val helper = new ErrorStateTestHelper

    helper.testErrorEvent(event, errorSource)
  }

  it should "manage multiple error sources" in {
    val helper = new ErrorStateTestHelper

    (1 to 4) foreach { idx =>
      val event = RadioSourceErrorEvent(radioSource(idx))
      helper.sendEvent(event)
    }

    (1 to 4) foreach { idx =>
      helper.checkSourceDataFor(idx).get.source should be(radioSource(idx))
    }
  }

  it should "not create multiple check actors for the same radio source" in {
    val errorSource = radioSource(17)
    val helper = new ErrorStateTestHelper

    helper.sendEvent(RadioPlaybackErrorEvent(errorSource))
      .sendEvent(RadioSourceErrorEvent(errorSource))
      .expectErrorStateUpdate(RadioControlProtocol.DisableSource(errorSource))

    val errorState = helper.querySourcesInErrorState()
    errorState.errorSources should contain only errorSource
    helper.checkSourceDataFor(2, await = false) shouldBe empty
    helper.expectNoErrorStateUpdate()
  }

  it should "remove a source from error state when the check actor stops itself" in {
    val source1 = radioSource(7)
    val source2 = radioSource(77)
    val helper = new ErrorStateTestHelper
    helper.sendEvent(RadioPlaybackErrorEvent(source1))
      .sendEvent(RadioSourceErrorEvent(source2))
      .expectErrorStateUpdate(RadioControlProtocol.DisableSource(source1))
      .expectErrorStateUpdate(RadioControlProtocol.DisableSource(source2))

    val sourceData = helper.checkSourceDataFor(1).get
    sourceData.probe.stop()

    helper.expectErrorStateUpdate(RadioControlProtocol.EnableSource(source1))
    val errorSources = helper.querySourcesInErrorState()
    errorSources.errorSources should contain only source2
  }

  it should "use a default Spawner" in {
    val errorSource = radioSource(23)
    val helper = new ErrorStateTestHelper(provideSpawner = false)

    helper.sendEvent(RadioPlaybackContextCreationFailedEvent(errorSource))

    val errorSources = helper.querySourcesInErrorState()
    errorSources.errorSources should contain only errorSource
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
      val scheduleMsg = probeScheduler.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      scheduleMsg.delay should be(delay)
      scheduleMsg.invocation match {
        case inv: ScheduledInvocationActor.TypedActorInvocation =>
          inv.receiver should be(checkScheduler)
          inv
        case inv => fail("Unexpected invocation: " + inv)
      }
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

  /**
    * A helper class for tests of the check source actor.
    *
    * @param provideSpawner flag whether an own [[Spawner]] implementation
    *                       should be provided
    */
  private class CheckSourceTestHelper(provideSpawner: Boolean = true) extends Spawner {
    /** Stores a unique actor name prefix for this test. */
    val namePrefix: String = ActorNamePrefix + counter.incrementAndGet()

    /** Test probe for the check scheduler actor. */
    private val probeScheduler = testKit.createTestProbe[ErrorStateActor.ScheduleCheckCommand]()

    /** Test probe for the actor managing playback context factories. */
    private val probePlaybackContextActor =
      testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** Test probe for the scheduled invocation actor. */
    private val probeScheduledInvocation =
      testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /**
      * Stores a test probe for the check playback actor. The dummy factory for
      * the check playback actor creates a new probe every time it is called.
      */
    private val refProbeCheckPlaybackActor = new AtomicReference[TypedTestProbe[ErrorStateActor.CheckPlaybackCommand]]

    /**
      * Stores the behavior for the playback actor. This is used by the
      * [[Spawner]] implementation to inject a test probe.
      */
    private val refCheckPlaybackBehavior = new AtomicReference[Behavior[ErrorStateActor.CheckPlaybackCommand]]

    /** Stores the name prefix passed to the check playback factory. */
    private val refNamePrefix = new AtomicReference[String]

    /** Stores the name of the check playback actor. */
    private val refCheckPlaybackActorName = new AtomicReference[String]

    /** Stores the scheduled invocation for the check timeout. */
    private val refTimeoutMessage = new AtomicReference[ScheduledInvocationActor.TypedActorInvocation]

    /** The actor to be tested. */
    private val checkActor = createCheckActor()

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def send(command: ErrorStateActor.CheckRadioSourceCommand): CheckSourceTestHelper = {
      checkActor ! command
      this
    }

    /**
      * Sends a command to the test actor that triggers a new check.
      *
      * @return this test helper
      */
    def triggerCheck(): CheckSourceTestHelper = {
      send(ErrorStateActor.RunRadioSourceCheck(probeScheduledInvocation.ref))
    }

    /**
      * Tests the name prefix used when creating a check playback actor.
      *
      * @param expPrefix the expected prefix
      * @return this test helper
      */
    def expectPlaybackNamePrefix(expPrefix: String): CheckSourceTestHelper = {
      awaitCond(refNamePrefix.get() == expPrefix)
      this
    }

    /**
      * Tests the name of the check playback actor.
      *
      * @param expName the expected name
      * @return this test helper
      */
    def expectCheckPlaybackActorName(expName: String): CheckSourceTestHelper = {
      awaitCond(refCheckPlaybackActorName.get() == expName)
      this
    }

    /**
      * Expects that the scheduler actor was invoked to reschedule a check.
      *
      * @param delay the expected delay
      * @return this test helper
      */
    def expectSchedule(delay: FiniteDuration): CheckSourceTestHelper =
      expectAddSchedule(checkActor, delay)

    /**
      * Tests whether the actor under test stopped itself.
      */
    def expectActorStopped(): Unit = {
      checkStopped(checkActor)
    }

    /**
      * Stops the current actor to check for playback. This means that the
      * current check fails.
      *
      * @return this test helper
      */
    def stopCheckPlaybackActor(): CheckSourceTestHelper = {
      probeCheckPlaybackActor.stop()
      refProbeCheckPlaybackActor set null
      this
    }

    /**
      * Expects that a timeout message has been sent to the check playback
      * actor.
      *
      * @return this test helper
      */
    def expectPlaybackActorTimeout(): CheckSourceTestHelper = {
      probeCheckPlaybackActor.expectMessage(ErrorStateActor.CheckTimeout)
      this
    }

    /**
      * Expects that no message has been sent to the check playback actor.
      *
      * @return this test helper
      */
    def expectNoPlaybackActorMessage(): CheckSourceTestHelper = {
      probeCheckPlaybackActor.expectNoMessage(200.millis)
      this
    }

    /**
      * Expects that a message has been sent to the scheduled invocation actor
      * to trigger a check timeout signal. The message is stored, so that it
      * can be simulated later.
      *
      * @return this test helper
      */
    def expectTimeoutSchedule(): CheckSourceTestHelper = {
      val invocation = probeScheduledInvocation.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      invocation.delay should be(TestRadioConfig.sourceCheckTimeout)
      refTimeoutMessage set invocation.invocation.asInstanceOf[ScheduledInvocationActor.TypedActorInvocation]
      this
    }

    /**
      * Simulates the scheduled invocation of the check timeout message.
      *
      * @return this test helper
      */
    def sendCheckTimeout(): CheckSourceTestHelper = {
      val invocation = refTimeoutMessage.get()
      invocation should not be null
      invocation.send()
      this
    }

    /**
      * @inheritdoc This implementation checks the behavior and returns a test
      *             probe.
      */
    override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] = {
      behavior should be(refCheckPlaybackBehavior.get())
      optName foreach refCheckPlaybackActorName.set
      refProbeCheckPlaybackActor.get().ref.asInstanceOf[ActorRef[T]]
    }

    /**
      * Checks whether an [[ErrorStateActor.AddScheduledCheck]] command is
      * passed to the scheduler actor.
      *
      * @param checkActor the check actor
      * @param delay      the delay
      * @return this test helper
      */
    private def expectAddSchedule(checkActor: ActorRef[ErrorStateActor.CheckRadioSourceCommand],
                                  delay: FiniteDuration): CheckSourceTestHelper = {
      probeScheduler.expectMessage(ErrorStateActor.AddScheduledCheck(checkActor, delay))
      this
    }

    /**
      * Returns the (dynamic) test probe for the current check playback actor.
      *
      * @return the probe for the current check playback actor
      */
    private def probeCheckPlaybackActor: TypedTestProbe[ErrorStateActor.CheckPlaybackCommand] = {
      awaitCond(refProbeCheckPlaybackActor.get() != null)
      refProbeCheckPlaybackActor.get()
    }

    /**
      * Creates the actor to be tested. Also tests the schedule of the initial
      * source check.
      *
      * @return the test check radio source actor
      */
    private def createCheckActor(): ActorRef[ErrorStateActor.CheckRadioSourceCommand] = {
      val behavior = ErrorStateActor.checkSourceBehavior(TestRadioConfig,
        TestRadioSource,
        namePrefix,
        probePlaybackContextActor.ref,
        probeScheduler.ref,
        createCheckPlaybackActorFactory(),
        optSpawner = if (provideSpawner) Some(this) else None)
      val actor = testKit.spawn(behavior)

      expectAddSchedule(actor, TestRadioConfig.retryFailedSource)
      actor
    }

    /**
      * Returns a dummy factory for creating a check playback actor that checks
      * the passed in parameters and returns a test probe.
      *
      * @return the dummy factory for a check playback actor
      */
    private def createCheckPlaybackActorFactory(): ErrorStateActor.CheckPlaybackActorFactory =
      (receiver: ActorRef[ErrorStateActor.RadioSourceCheckSuccessful],
       radioSource: RadioSource,
       namePrefix: String,
       factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
       config: PlayerConfig,
       _: ErrorStateActor.PlaybackActorsFactory) => {
        receiver should be(checkActor)
        radioSource should be(TestRadioSource)
        factoryActor should be(probePlaybackContextActor.ref)
        config should be(TestPlayerConfig)
        refNamePrefix set namePrefix

        refProbeCheckPlaybackActor set testKit.createTestProbe[ErrorStateActor.CheckPlaybackCommand]()
        val behavior = Behaviors.monitor[ErrorStateActor.CheckPlaybackCommand](refProbeCheckPlaybackActor.get().ref,
          Behaviors.ignore)
        refCheckPlaybackBehavior set behavior
        behavior
      }
  }

  /**
    * A data class that collects data about a test probe for checking a
    * specific radio source.
    *
    * @param probe  the test probe
    * @param source the associated radio source
    */
  private case class CheckSourceData(probe: TypedTestProbe[ErrorStateActor.CheckRadioSourceCommand],
                                     source: RadioSource)

  /**
    * A test helper class managing an error state actor and its dependencies.
    *
    * @param provideSpawner flag whether a spawner should be provided
    */
  private class ErrorStateTestHelper(provideSpawner: Boolean = true) extends Spawner {
    /** Test probe for the enabled state actor. */
    private val probeEnabledStateActor = testKit.createTestProbe[RadioControlProtocol.SourceEnabledStateCommand]()

    /** Test probe for the playback context factory actor. */
    private val probeFactoryActor = testKit.createTestProbe[PlaybackContextFactoryActor.PlaybackContextCommand]()

    /** Test probe for the scheduled invocation actor. */
    private val probeScheduledInvActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the event manager actor. */
    private val probeEventActor = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the check scheduler actor. */
    private val probeSchedulerActor = testKit.createTestProbe[ErrorStateActor.ScheduleCheckCommand]()

    /** The behavior for the check scheduler actor. */
    private val schedulerBehavior = Behaviors.monitor[ErrorStateActor.ScheduleCheckCommand](probeSchedulerActor.ref,
      Behaviors.ignore)

    /**
      * A hash map allowing to associate behaviors for check source actors with
      * the corresponding test probes.
      */
    private val checkSourceBehaviors = new ConcurrentHashMap[Behavior[ErrorStateActor.CheckRadioSourceCommand],
      TypedTestProbe[ErrorStateActor.CheckRadioSourceCommand]]

    /**
      * A hash map allowing to associate the name prefixes for check source
      * actors with the corresponding test probes.
      */
    private val checkSourceByName = new ConcurrentHashMap[String, CheckSourceData]

    /** Stores the event listener actor registered by the test actor. */
    private var eventListener: ActorRef[RadioEvent] = _

    /** The actor to be tested. */
    private val stateActor = createStateActor()

    /**
      * Queries the test actor for information about sources in error state.
      *
      * @return the object with the corresponding information
      */
    def querySourcesInErrorState(): ErrorStateActor.SourcesInErrorState = {
      val probe = testKit.createTestProbe[ErrorStateActor.SourcesInErrorState]()
      stateActor ! ErrorStateActor.GetSourcesInErrorState(probe.ref)
      probe.expectMessageType[ErrorStateActor.SourcesInErrorState]
    }

    /**
      * Sends the given event to the event listener registered by the test
      * actor.
      *
      * @param event the event
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): ErrorStateTestHelper = {
      eventListener ! event
      this
    }

    /**
      * Returns information for the error source with the given index. Since
      * this information is creates asynchronously, it is possible to wait for
      * it.
      *
      * @param index the index of the error source in question
      * @param await flag whether to wait until information is available
      * @return an ''Option'' with the retrieved information
      */
    def checkSourceDataFor(index: Int, await: Boolean = true): Option[CheckSourceData] = {
      val key = ErrorStateActor.ActorNamePrefix + index
      if (await) {
        awaitCond(checkSourceByName.containsKey(key))
      }
      Option(checkSourceByName.get(key))
    }

    /**
      * Expects a command to update the error state being sent to the
      * corresponding management actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectErrorStateUpdate(command: RadioControlProtocol.SourceEnabledStateCommand): ErrorStateTestHelper = {
      probeEnabledStateActor.expectMessage(command)
      this
    }

    /**
      * Expects that no update command was sent to the error state management
      * actor.
      *
      * @return this test helper
      */
    def expectNoErrorStateUpdate(): ErrorStateTestHelper = {
      probeEnabledStateActor.expectNoMessage(200.millis)
      this
    }

    /**
      * Tests whether an event indicating an error radio source is correctly
      * handled.
      *
      * @param event       the event
      * @param errorSource the error source
      */
    def testErrorEvent(event: RadioEvent, errorSource: RadioSource): Unit = {
      sendEvent(event)
      val data = checkSourceDataFor(1).get
      data.source should be(errorSource)
      expectErrorStateUpdate(RadioControlProtocol.DisableSource(errorSource))
    }

    override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
      if (behavior == schedulerBehavior) {
        optName should be(Some("radioErrorStateSchedulerActor"))
        probeSchedulerActor.ref.asInstanceOf[ActorRef[T]]
      } else {
        val probeCheck = checkSourceBehaviors.get(behavior)
        probeCheck should not be null
        probeCheck.ref.asInstanceOf[ActorRef[T]]
      }

    /**
      * Creates an actor instance to be tested.
      *
      * @return
      */
    private def createStateActor(): ActorRef[ErrorStateActor.ErrorStateCommand] = {
      val behavior = ErrorStateActor.errorStateBehavior(TestRadioConfig,
        probeEnabledStateActor.ref,
        probeFactoryActor.ref,
        probeScheduledInvActor.ref,
        probeEventActor.ref,
        createSchedulerFactory(),
        createCheckSourceFactory(),
        if (provideSpawner) Some(this) else None)
      val actor = testKit.spawn(behavior)

      val registration = probeEventActor.expectMessageType[EventManagerActor.RegisterListener[RadioEvent]]
      eventListener = registration.listener
      actor
    }

    /**
      * Returns a factory for creating a behavior for the check scheduler
      * actor.
      *
      * @return the factory for the check scheduler actor
      */
    private def createSchedulerFactory(): ErrorStateActor.CheckSchedulerActorFactory =
      (scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand]) => {
        scheduleActor should be(probeScheduledInvActor.ref)
        schedulerBehavior
      }

    /**
      * Returns a factory for creating a behavior for an actor to check a radio
      * source. This behavior is created dynamically and associated with a test
      * probe.
      *
      * @return the factory for a check source actor
      */
    private def createCheckSourceFactory(): ErrorStateActor.CheckSourceActorFactory =
      (config: RadioPlayerConfig,
       source: RadioSource,
       namePrefix: String,
       factoryActor: ActorRef[PlaybackContextFactoryActor.PlaybackContextCommand],
       scheduler: ActorRef[ErrorStateActor.ScheduleCheckCommand],
       _: ErrorStateActor.CheckPlaybackActorFactory,
       _: Option[Spawner]) => {
        config should be(TestRadioConfig)
        factoryActor should be(probeFactoryActor.ref)
        if (provideSpawner) {
          scheduler should be(probeSchedulerActor.ref)
        }

        val checkProbe = testKit.createTestProbe[ErrorStateActor.CheckRadioSourceCommand]()
        val checkBehavior = Behaviors.monitor[ErrorStateActor.CheckRadioSourceCommand](checkProbe.ref,
          Behaviors.ignore)
        checkSourceBehaviors.put(checkBehavior, checkProbe)
        checkSourceByName.put(namePrefix, CheckSourceData(checkProbe, source))
        checkBehavior
      }
  }
}
