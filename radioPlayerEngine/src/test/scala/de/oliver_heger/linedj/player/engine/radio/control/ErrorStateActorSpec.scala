/*
 * Copyright 2015-2025 The Developers Team.
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

import com.github.cloudfiles.core.http.factory.Spawner
import de.oliver_heger.linedj.player.engine.*
import de.oliver_heger.linedj.player.engine.actors.*
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.radio.Fixtures.TestPlayerConfig
import de.oliver_heger.linedj.player.engine.radio.config.RadioPlayerConfig
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioStreamHandle, RadioStreamHandleManagerActor}
import org.apache.pekko.actor as classic
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe as TypedTestProbe}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Props}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import javax.sound.sampled.{AudioFormat, AudioInputStream}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Random, Success, Try}

object ErrorStateActorSpec:
  /** A prefix for actor names. */
  private val ActorNamePrefix = "ErrorCheck_"

  /** A format used by the test audio input streams. */
  final val Format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1, 16, 2, 17, 44100.0, false)

  /** A test radio player configuration. */
  private val TestRadioConfig = RadioPlayerConfig(playerConfig = TestPlayerConfig,
    retryFailedSource = 11.seconds,
    retryFailedSourceIncrement = 2.5,
    maxRetryFailedSource = 30.seconds,
    sourceCheckTimeout = 44.seconds,
    maximumEvalDelay = 1.hour,
    retryFailedReplacement = 30.seconds,
    metadataCheckTimeout = 45.seconds,
    streamCacheTime = 10.seconds,
    stalledPlaybackCheck = 1.minute)

  /** A radio source used by tests */
  private val TestRadioSource = RadioSource("http://radio.example.org/music.m3u", Some("mp3"))

  /** A counter for generating unique actor names. */
  private val counter = new AtomicInteger
end ErrorStateActorSpec

/**
  * Test class for the [[ErrorStateActor]] module.
  */
class ErrorStateActorSpec(testSystem: classic.ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(classic.ActorSystem("ErrorStateActorSpec"))

  /** The test kit for testing typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    TestKit shutdownActorSystem system
    super.afterAll()

  import ErrorStateActorSpec.*

  /**
    * Tests whether the given actor has been stopped.
    *
    * @param actor the actor to check
    * @tparam T the message type of this actor
    */
  private def checkStopped[T](actor: ActorRef[T]): Unit =
    val probe = testKit.createDeadLetterProbe()
    probe.expectTerminated(actor)

  "Check playback actor" should "execute a successful check for the current radio source" in :
    val helper = new CheckPlaybackTestHelper
    val handle = helper.createHandle()

    val checkActor = helper.createCheckPlaybackActor()

    helper.expectAndAnswerHandleRequest(Success(handle))
      .expectSuccess()
    Mockito.verify(handle, Mockito.timeout(3000)).cancelStream()
    checkStopped(checkActor)

  it should "handle a timeout command before the handle is available" in :
    val helper = new CheckPlaybackTestHelper
    val handle = helper.createHandle()
    val checkActor = helper.createCheckPlaybackActor()

    checkActor ! ErrorStateActor.CheckTimeout

    helper.expectNoSuccess()
      .expectAndAnswerHandleRequest(Success(handle))
      .expectNoSuccess()
    Mockito.verify(handle, Mockito.timeout(3000)).cancelStream()
    checkStopped(checkActor)

  it should "handle a timeout command when waiting for audio playback" in :
    val helper = new CheckPlaybackTestHelper
    val handle = helper.createHandle(delayed = true)
    val checkActor = helper.createCheckPlaybackActor()

    helper.expectAndAnswerHandleRequest(Success(handle))
    checkActor ! ErrorStateActor.CheckTimeout

    helper.expectNoSuccess()
    Mockito.verify(handle, Mockito.timeout(3000)).cancelStream()
    checkStopped(checkActor)

  it should "handle a failed request for a stream handle" in :
    val helper = new CheckPlaybackTestHelper
    val checkActor = helper.createCheckPlaybackActor()

    helper.expectAndAnswerHandleRequest(Failure(new IllegalStateException("Test exception: No handle.")))
      .expectNoSuccess()

    checkStopped(checkActor)

  it should "handle a failed operation to attach to the handle" in :
    val handle = mock[RadioStreamHandle]

    given classic.ActorSystem = any()

    Mockito.when(handle.attachAudioSinkOrCancel(any()))
      .thenReturn(Future.failed(new IllegalStateException("Test exception: Failed to attach to handle.")))
    val helper = new CheckPlaybackTestHelper
    val checkActor = helper.createCheckPlaybackActor()

    helper.expectAndAnswerHandleRequest(Success(handle))
      .expectNoSuccess()

    checkStopped(checkActor)

  it should "handle a failed audio stream" in :
    val helper = new CheckPlaybackTestHelper
    val handle = helper.createHandle()
    val checkActor = helper.failAudioStreamCreation()
      .createCheckPlaybackActor()

    helper.expectAndAnswerHandleRequest(Success(handle))
      .expectNoSuccess()

    checkStopped(checkActor)
    Mockito.verify(handle).cancelStream()

  "Check scheduler actor" should "schedule check commands for radio sources" in :
    val helper = new CheckSchedulerTestHelper

    val probe = helper.handleSchedule()

    probe.expectMessage(helper.runCheckCommand)

  it should "only trigger one check at a given time" in :
    val helper = new CheckSchedulerTestHelper
    helper.handleSchedule()

    val probe = helper.handleSchedule()

    probe.expectNoMessage(200.millis)

  it should "continue with the next pending check if the current one is rescheduled" in :
    val helper = new CheckSchedulerTestHelper
    val probe1 = helper.handleSchedule()
    val probe2 = helper.handleSchedule()
    val probe3 = helper.handleSchedule()

    helper.send(ErrorStateActor.AddScheduledCheck(probe1.ref, 3.minutes))

    probe2.expectMessage(helper.runCheckCommand)
    helper.send(ErrorStateActor.AddScheduledCheck(probe2.ref, 20.seconds))

    probe3.expectMessage(helper.runCheckCommand)

  it should "reset the current check when it is complete" in :
    val helper = new CheckSchedulerTestHelper
    val probe1 = helper.handleSchedule()
    helper.send(ErrorStateActor.AddScheduledCheck(probe1.ref, 10.seconds))
      .expectScheduledInvocation(10.seconds)

    val probe2 = helper.handleSchedule()

    probe2.expectMessage(helper.runCheckCommand)

  it should "handle the termination of the current check actor" in :
    val helper = new CheckSchedulerTestHelper
    val probe1 = helper.handleSchedule()

    probe1.stop()

    val probe2 = helper.handleSchedule()
    probe2.expectMessage(helper.runCheckCommand)

  "Check radio source actor" should "execute a successful test" in :
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .expectPlaybackNamePrefix(helper.namePrefix + "_1_")
      .expectCheckPlaybackActorName(helper.namePrefix + "_1_check")
      .send(ErrorStateActor.RadioSourceCheckSuccessful())
      .stopCheckPlaybackActor()
      .expectActorStopped()

  it should "use a default spawner" in :
    val helper = new CheckSourceTestHelper(provideSpawner = false)

    helper.triggerCheck()

    val probe = testKit.createDeadLetterProbe()
    helper.triggerCheck()
    probe.expectNoMessage(200.millis)

  it should "reschedule a check if the current one failed" in :
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .stopCheckPlaybackActor()
      .expectSchedule(27500.millis)
      .triggerCheck()
      .expectPlaybackNamePrefix(helper.namePrefix + "_2_")

  it should "take the maximum retry delay into account" in :
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .stopCheckPlaybackActor()
      .expectSchedule(27500.millis)
      .triggerCheck()
      .stopCheckPlaybackActor()
      .expectSchedule(TestRadioConfig.maxRetryFailedSource)

  it should "handle timeouts correctly" in :
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .expectTimeoutSchedule()
      .sendCheckTimeout()
      .expectPlaybackActorTimeout()

  it should "not stop itself before the current check actor is stopped" in :
    val helper = new CheckSourceTestHelper
    helper.triggerCheck()
      .expectTimeoutSchedule()
      .send(ErrorStateActor.RadioSourceCheckSuccessful())

    val probe = testKit.createDeadLetterProbe()
    helper.sendCheckTimeout()

    probe.expectNoMessage(200.millis)

  it should "not send a timeout to the check playback actor if the check is already successful" in :
    val helper = new CheckSourceTestHelper

    helper.triggerCheck()
      .expectTimeoutSchedule()
      .send(ErrorStateActor.RadioSourceCheckSuccessful())
      .sendCheckTimeout()
      .expectNoPlaybackActorMessage()

  it should "ignore a timeout for a previous check" in :
    val helper = new CheckSourceTestHelper
    helper.triggerCheck()
      .expectTimeoutSchedule()
      .stopCheckPlaybackActor()
      .triggerCheck()

    val probe = testKit.createDeadLetterProbe()
    helper.sendCheckTimeout()
      .expectNoPlaybackActorMessage()

    probe.expectNoMessage(100.millis)

  "Error state actor" should "return an initial empty error state" in :
    val helper = new ErrorStateTestHelper

    val errorSources = helper.querySourcesInErrorState()

    errorSources.errorSources shouldBe empty

  it should "handle a radio source playback error event" in :
    val errorSource = radioSource(7)
    val event = RadioPlaybackErrorEvent(errorSource)
    val helper = new ErrorStateTestHelper

    helper.testErrorEvent(event, errorSource)

  it should "handle a radio source playback context creation failed event" in :
    val errorSource = radioSource(11)
    val event = RadioPlaybackContextCreationFailedEvent(errorSource)
    val helper = new ErrorStateTestHelper

    helper.testErrorEvent(event, errorSource)

  it should "handle a radio source error event" in :
    val errorSource = radioSource(13)
    val event = RadioSourceErrorEvent(errorSource)
    val helper = new ErrorStateTestHelper

    helper.testErrorEvent(event, errorSource)

  it should "manage multiple error sources" in :
    val helper = new ErrorStateTestHelper

    (1 to 4) foreach { idx =>
      val event = RadioSourceErrorEvent(radioSource(idx))
      helper.sendEvent(event)
    }

    (1 to 4) foreach { idx =>
      helper.checkSourceDataFor(idx).get.source should be(radioSource(idx))
    }

  it should "not create multiple check actors for the same radio source" in :
    val errorSource = radioSource(17)
    val helper = new ErrorStateTestHelper

    helper.sendEvent(RadioPlaybackErrorEvent(errorSource))
      .sendEvent(RadioSourceErrorEvent(errorSource))
      .expectErrorStateUpdate(RadioControlProtocol.DisableSource(errorSource))

    val errorState = helper.querySourcesInErrorState()
    errorState.errorSources should contain only errorSource
    helper.checkSourceDataFor(2, await = false) shouldBe empty
    helper.expectNoErrorStateUpdate()

  it should "remove a source from error state when the check actor stops itself" in :
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

  it should "use a default Spawner" in :
    val errorSource = radioSource(23)
    val helper = new ErrorStateTestHelper(provideSpawner = false)

    helper.sendEvent(RadioPlaybackContextCreationFailedEvent(errorSource))

    val errorSources = helper.querySourcesInErrorState()
    errorSources.errorSources should contain only errorSource
  
  /**
    * A test helper implementation for testing the check playback actor.
    */
  private class CheckPlaybackTestHelper:
    /**
      * Test probe for the actor receiving the result of a radio source check.
      */
    private val probeReceiverActor = testKit.createTestProbe[ErrorStateActor.RadioSourceCheckSuccessful]()

    /** Test probe for the stream handle manager actor. */
    private val probeHandleManagerActor =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

    /** The mock for the audio stream factory. */
    private val mockStreamFactory = createStreamFactoryMock()

    /** An object for creating random data. */
    private val random = Random()

    /**
      * Creates a check playback actor instance that is configured with test
      * settings and this factory.
      *
      * @return the check actor instance
      */
    def createCheckPlaybackActor(): ActorRef[ErrorStateActor.CheckPlaybackCommand] =
      val behavior = ErrorStateActor.checkPlaybackBehavior(probeReceiverActor.ref,
        TestRadioSource,
        ActorNamePrefix,
        mockStreamFactory,
        TestPlayerConfig,
        probeHandleManagerActor.ref)
      testKit.spawn(behavior)

    /**
      * Expects a request to the stream handle manager actor and answers it
      * with the provided handle.
      *
      * @param triedHandle the handle to put into the response
      * @return this test helper
      */
    def expectAndAnswerHandleRequest(triedHandle: Try[RadioStreamHandle]): CheckPlaybackTestHelper =
      val request = probeHandleManagerActor.expectMessageType[RadioStreamHandleManagerActor.GetStreamHandle]
      request.params.streamSource should be(TestRadioSource)
      request.params.streamName should be(ActorNamePrefix + "_errorCheck")
      val response = RadioStreamHandleManagerActor.GetStreamHandleResponse(TestRadioSource, triedHandle, None)
      request.replyTo ! response
      this

    /**
      * Expects that a success message was sent to the receiver actor.
      *
      * @return this factory
      */
    def expectSuccess(): CheckPlaybackTestHelper =
      probeReceiverActor.expectMessage(ErrorStateActor.RadioSourceCheckSuccessful())
      this

    /**
      * Expects that no success message was sent to the receiver actor.
      *
      * @return this factory
      */
    def expectNoSuccess(): CheckPlaybackTestHelper =
      probeReceiverActor.expectNoMessage(250.millis)
      this

    /**
      * Creates a mock for a [[RadioStreamHandle]] that is prepared to handle
      * an operation to attach for audio data.
      *
      * @param delayed flag whether the data source of the handle should send
      *                data with a delay
      * @return the mock handle
      */
    def createHandle(delayed: Boolean = false): RadioStreamHandle =
      val data = ByteString(random.nextBytes(4096)).grouped(512).toList
      val source = Source(data)
      val delayedSource = if delayed then source.delay(1.second) else source
      val handle = mock[RadioStreamHandle]

      given classic.ActorSystem = any()

      Mockito.when(handle.attachAudioSinkOrCancel(any()))
        .thenReturn(Future.successful(delayedSource))
      handle

    /**
      * Prepares the mock for the audio stream factory to return a failure
      * result. This should cause the whole audio stream to fail.
      *
      * @return this test helper
      */
    def failAudioStreamCreation(): CheckPlaybackTestHelper =
      val future: Future[AudioStreamFactory.AudioStreamPlaybackData] =
        Future.failed(new IllegalStateException("Test exception: No audio stream."))
      Mockito.doReturn(future).when(mockStreamFactory).playbackDataForAsync(any())
      this

    /**
      * Creates a mock for an audio stream factory that is prepared to return
      * a dummy audio stream.
      *
      * @return the mock stream factory
      */
    private def createStreamFactoryMock(): AsyncAudioStreamFactory =
      val factory = mock[AsyncAudioStreamFactory]
      Mockito.when(factory.playbackDataForAsync(any())).thenAnswer((invocation: InvocationOnMock) =>
        val uri = invocation.getArgument[String](0)
        uri should be(TestRadioSource.uriWithExtension)
        val creator: AudioStreamFactory.AudioStreamCreator = in =>
          new AudioInputStream(in, Format, 8192)
        Future.successful(AudioStreamFactory.AudioStreamPlaybackData(creator, 1024)))
      factory

  /**
    * A test helper class for tests of the check scheduler actor.
    */
  private class CheckSchedulerTestHelper:
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
    def send(command: ErrorStateActor.ScheduleCheckCommand): CheckSchedulerTestHelper =
      checkScheduler ! command
      this

    /**
      * Expects that a scheduled invocation command was issued with the given
      * properties.
      *
      * @param delay the delay
      * @return the invocation
      */
    def expectScheduledInvocation(delay: FiniteDuration): ScheduledInvocationActor.TypedActorInvocation =
      val scheduleMsg = probeScheduler.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      scheduleMsg.delay should be(delay)
      scheduleMsg.invocation match
        case inv: ScheduledInvocationActor.TypedActorInvocation =>
          inv.receiver should be(checkScheduler)
          inv
        case inv => fail("Unexpected invocation: " + inv)

    /**
      * Simulates a complete schedule for a newly created test probe. A command
      * to add a schedule for this probe is sent to the test actor, as well as
      * the answer from the scheduled invocation actor. Returns the test probe.
      *
      * @return the test probe simulating a check actor
      */
    def handleSchedule(): TypedTestProbe[ErrorStateActor.CheckRadioSourceCommand] =
      val delay = 90.seconds
      val probe = testKit.createTestProbe[ErrorStateActor.CheckRadioSourceCommand]()
      send(ErrorStateActor.AddScheduledCheck(probe.ref, delay))

      val invocation = expectScheduledInvocation(delay)
      invocation.receiver ! invocation.message
      probe

    /**
      * Returns the command to run a check on the current radio source as
      * expected by the actor under test.
      *
      * @return the expected command to check the current source
      */
    def runCheckCommand: ErrorStateActor.RunRadioSourceCheck =
      ErrorStateActor.RunRadioSourceCheck(probeScheduler.ref)

  /**
    * A helper class for tests of the check source actor.
    *
    * @param provideSpawner flag whether an own [[Spawner]] implementation
    *                       should be provided
    */
  private class CheckSourceTestHelper(provideSpawner: Boolean = true) extends Spawner:
    /** Stores a unique actor name prefix for this test. */
    val namePrefix: String = ActorNamePrefix + counter.incrementAndGet()

    /** Test probe for the check scheduler actor. */
    private val probeScheduler = testKit.createTestProbe[ErrorStateActor.ScheduleCheckCommand]()

    /** Test probe for the scheduled invocation actor. */
    private val probeScheduledInvocation =
      testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the stream handle manager actor. */
    private val probeHandleManagerActor =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

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

    /** The mock for the audio stream factory. */
    private val mockAudioFactory = mock[AsyncAudioStreamFactory]

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
    def send(command: ErrorStateActor.CheckRadioSourceCommand): CheckSourceTestHelper =
      checkActor ! command
      this

    /**
      * Sends a command to the test actor that triggers a new check.
      *
      * @return this test helper
      */
    def triggerCheck(): CheckSourceTestHelper =
      send(ErrorStateActor.RunRadioSourceCheck(probeScheduledInvocation.ref))

    /**
      * Tests the name prefix used when creating a check playback actor.
      *
      * @param expPrefix the expected prefix
      * @return this test helper
      */
    def expectPlaybackNamePrefix(expPrefix: String): CheckSourceTestHelper =
      awaitCond(refNamePrefix.get() == expPrefix)
      this

    /**
      * Tests the name of the check playback actor.
      *
      * @param expName the expected name
      * @return this test helper
      */
    def expectCheckPlaybackActorName(expName: String): CheckSourceTestHelper =
      awaitCond(refCheckPlaybackActorName.get() == expName)
      this

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
    def expectActorStopped(): Unit =
      checkStopped(checkActor)

    /**
      * Stops the current actor to check for playback. This means that the
      * current check fails.
      *
      * @return this test helper
      */
    def stopCheckPlaybackActor(): CheckSourceTestHelper =
      probeCheckPlaybackActor.stop()
      refProbeCheckPlaybackActor set null
      this

    /**
      * Expects that a timeout message has been sent to the check playback
      * actor.
      *
      * @return this test helper
      */
    def expectPlaybackActorTimeout(): CheckSourceTestHelper =
      probeCheckPlaybackActor.expectMessage(ErrorStateActor.CheckTimeout)
      this

    /**
      * Expects that no message has been sent to the check playback actor.
      *
      * @return this test helper
      */
    def expectNoPlaybackActorMessage(): CheckSourceTestHelper =
      probeCheckPlaybackActor.expectNoMessage(200.millis)
      this

    /**
      * Expects that a message has been sent to the scheduled invocation actor
      * to trigger a check timeout signal. The message is stored, so that it
      * can be simulated later.
      *
      * @return this test helper
      */
    def expectTimeoutSchedule(): CheckSourceTestHelper =
      val invocation = probeScheduledInvocation.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]
      invocation.delay should be(TestRadioConfig.sourceCheckTimeout)
      refTimeoutMessage set invocation.invocation.asInstanceOf[ScheduledInvocationActor.TypedActorInvocation]
      this

    /**
      * Simulates the scheduled invocation of the check timeout message.
      *
      * @return this test helper
      */
    def sendCheckTimeout(): CheckSourceTestHelper =
      val invocation = refTimeoutMessage.get()
      invocation should not be null
      invocation.send()
      this

    /**
      * @inheritdoc This implementation checks the behavior and returns a test
      *             probe.
      */
    override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
      behavior should be(refCheckPlaybackBehavior.get())
      optName foreach refCheckPlaybackActorName.set
      refProbeCheckPlaybackActor.get().ref.asInstanceOf[ActorRef[T]]

    /**
      * Checks whether an [[ErrorStateActor.AddScheduledCheck]] command is
      * passed to the scheduler actor.
      *
      * @param checkActor the check actor
      * @param delay      the delay
      * @return this test helper
      */
    private def expectAddSchedule(checkActor: ActorRef[ErrorStateActor.CheckRadioSourceCommand],
                                  delay: FiniteDuration): CheckSourceTestHelper =
      probeScheduler.expectMessage(ErrorStateActor.AddScheduledCheck(checkActor, delay))
      this

    /**
      * Returns the (dynamic) test probe for the current check playback actor.
      *
      * @return the probe for the current check playback actor
      */
    private def probeCheckPlaybackActor: TypedTestProbe[ErrorStateActor.CheckPlaybackCommand] =
      awaitCond(refProbeCheckPlaybackActor.get() != null)
      refProbeCheckPlaybackActor.get()

    /**
      * Creates the actor to be tested. Also tests the schedule of the initial
      * source check.
      *
      * @return the test check radio source actor
      */
    private def createCheckActor(): ActorRef[ErrorStateActor.CheckRadioSourceCommand] =
      val behavior = ErrorStateActor.checkSourceBehavior(TestRadioConfig,
        TestRadioSource,
        namePrefix,
        mockAudioFactory,
        probeScheduler.ref,
        probeHandleManagerActor.ref,
        createCheckPlaybackActorFactory(),
        optSpawner = if provideSpawner then Some(this) else None)
      val actor = testKit.spawn(behavior)

      expectAddSchedule(actor, TestRadioConfig.retryFailedSource)
      actor

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
       factoryActor: AsyncAudioStreamFactory,
       config: PlayerConfig,
       streamManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand]) => {
        receiver should be(checkActor)
        radioSource should be(TestRadioSource)
        factoryActor should be(mockAudioFactory)
        streamManager should be(probeHandleManagerActor.ref)
        config should be(TestPlayerConfig)
        refNamePrefix set namePrefix

        refProbeCheckPlaybackActor set testKit.createTestProbe[ErrorStateActor.CheckPlaybackCommand]()
        val behavior = Behaviors.monitor[ErrorStateActor.CheckPlaybackCommand](refProbeCheckPlaybackActor.get().ref,
          Behaviors.ignore)
        refCheckPlaybackBehavior set behavior
        behavior
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
  private class ErrorStateTestHelper(provideSpawner: Boolean = true) extends Spawner:
    /** Mock for the audio stream factory. */
    private val mockStreamFactory = mock[AsyncAudioStreamFactory]

    /** Test probe for the enabled state actor. */
    private val probeEnabledStateActor = testKit.createTestProbe[RadioControlProtocol.SourceEnabledStateCommand]()

    /** Test probe for the scheduled invocation actor. */
    private val probeScheduledInvActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the event manager actor. */
    private val probeEventActor = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the check scheduler actor. */
    private val probeSchedulerActor = testKit.createTestProbe[ErrorStateActor.ScheduleCheckCommand]()

    /** Test probe for the stream handle manager actor. */
    private val probeHandleManagerActor =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

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
    def querySourcesInErrorState(): ErrorStateActor.SourcesInErrorState =
      val probe = testKit.createTestProbe[ErrorStateActor.SourcesInErrorState]()
      stateActor ! ErrorStateActor.GetSourcesInErrorState(probe.ref)
      probe.expectMessageType[ErrorStateActor.SourcesInErrorState]

    /**
      * Sends the given event to the event listener registered by the test
      * actor.
      *
      * @param event the event
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): ErrorStateTestHelper =
      eventListener ! event
      this

    /**
      * Returns information for the error source with the given index. Since
      * this information is creates asynchronously, it is possible to wait for
      * it.
      *
      * @param index the index of the error source in question
      * @param await flag whether to wait until information is available
      * @return an ''Option'' with the retrieved information
      */
    def checkSourceDataFor(index: Int, await: Boolean = true): Option[CheckSourceData] =
      val key = ErrorStateActor.ActorNamePrefix + index
      if await then
        awaitCond(checkSourceByName.containsKey(key))
      Option(checkSourceByName.get(key))

    /**
      * Expects a command to update the error state being sent to the
      * corresponding management actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectErrorStateUpdate(command: RadioControlProtocol.SourceEnabledStateCommand): ErrorStateTestHelper =
      probeEnabledStateActor.expectMessage(command)
      this

    /**
      * Expects that no update command was sent to the error state management
      * actor.
      *
      * @return this test helper
      */
    def expectNoErrorStateUpdate(): ErrorStateTestHelper =
      probeEnabledStateActor.expectNoMessage(200.millis)
      this

    /**
      * Tests whether an event indicating an error radio source is correctly
      * handled.
      *
      * @param event       the event
      * @param errorSource the error source
      */
    def testErrorEvent(event: RadioEvent, errorSource: RadioSource): Unit =
      sendEvent(event)
      val data = checkSourceDataFor(1).get
      data.source should be(errorSource)
      expectErrorStateUpdate(RadioControlProtocol.DisableSource(errorSource))

    override def spawn[T](behavior: Behavior[T], optName: Option[String], props: Props): ActorRef[T] =
      if behavior == schedulerBehavior then
        optName should be(Some("radioErrorStateSchedulerActor"))
        probeSchedulerActor.ref.asInstanceOf[ActorRef[T]]
      else
        val probeCheck = checkSourceBehaviors.get(behavior)
        probeCheck should not be null
        probeCheck.ref.asInstanceOf[ActorRef[T]]

    /**
      * Creates an actor instance to be tested.
      *
      * @return
      */
    private def createStateActor(): ActorRef[ErrorStateActor.ErrorStateCommand] =
      val behavior = ErrorStateActor.errorStateBehavior(TestRadioConfig,
        probeEnabledStateActor.ref,
        mockStreamFactory,
        probeScheduledInvActor.ref,
        probeEventActor.ref,
        probeHandleManagerActor.ref,
        createSchedulerFactory(),
        createCheckSourceFactory(),
        if provideSpawner then Some(this) else None)
      val actor = testKit.spawn(behavior)

      val registration = probeEventActor.expectMessageType[EventManagerActor.RegisterListener[RadioEvent]]
      eventListener = registration.listener
      actor

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
       streamFactory: AsyncAudioStreamFactory,
       scheduler: ActorRef[ErrorStateActor.ScheduleCheckCommand],
       handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
       _: ErrorStateActor.CheckPlaybackActorFactory,
       _: Option[Spawner]) => {
        config should be(TestRadioConfig)
        streamFactory should be(mockStreamFactory)
        handleManager should be(probeHandleManagerActor.ref)
        if provideSpawner then
          scheduler should be(probeSchedulerActor.ref)

        val checkProbe = testKit.createTestProbe[ErrorStateActor.CheckRadioSourceCommand]()
        val checkBehavior = Behaviors.monitor[ErrorStateActor.CheckRadioSourceCommand](checkProbe.ref,
          Behaviors.ignore)
        checkSourceBehaviors.put(checkBehavior, checkProbe)
        checkSourceByName.put(namePrefix, CheckSourceData(checkProbe, source))
        checkBehavior
      }
