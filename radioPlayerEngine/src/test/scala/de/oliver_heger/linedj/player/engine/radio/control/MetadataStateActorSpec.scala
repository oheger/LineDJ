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

import de.oliver_heger.linedj.ActorTestKitSupport
import de.oliver_heger.linedj.player.engine.AudioSource
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQuery, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.interval.{IntervalTypes, LazyDate}
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig}
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioStreamHandle, RadioStreamHandleManagerActor}
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import org.apache.pekko.stream.{Attributes, Outlet, SourceShape}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{NotUsed, actor as classic}
import org.mockito.ArgumentMatchers.{any, eq as eqArg}
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.time.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.regex.Pattern
import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object MetadataStateActorSpec:
  /** Constant for a reference instant. */
  private val RefInstant = Instant.parse("2023-03-31T20:37:16Z")

  /** The reference local time corresponding to the reference instant. */
  private val RefTime = LocalDateTime.ofInstant(RefInstant, ZoneOffset.UTC)

  /**
    * The number of seconds corresponding to a single tick of the test clock.
    */
  private val TickSeconds = 10

  /** Metadata to match an exclusion due to bad music. */
  private val MetaBadMusic = "Bad music"

  /** Metadata to match another exclusion. */
  private val MetaNotWanted = "skip this"

  /** A radio source used by tests. */
  private val TestRadioSource = RadioSource("sourceWithExcludedMetadata")

  /** A test audio source representing the resolved audio stream. */
  private val TestAudioSource = AudioSource("resolvedStreamURI", 0, 0, 0)

  /** A test radio player configuration. */
  private val TestRadioConfig = RadioPlayerConfig(playerConfig = Fixtures.TestPlayerConfig,
    metadataCheckTimeout = 99.seconds,
    maximumEvalDelay = 2.hours,
    retryFailedReplacement = 1.minute,
    retryFailedSource = 50.seconds,
    retryFailedSourceIncrement = 3.0,
    maxRetryFailedSource = 20.hours,
    sourceCheckTimeout = 1.minute,
    streamCacheTime = 3.seconds,
    stalledPlaybackCheck = 30.seconds)

  /** A regular expression pattern to extract artist and song title. */
  private val RegSongData = Pattern.compile(s"(?<${MetadataConfig.ArtistGroup}>[^/]+)/\\s*" +
    s"(?<${MetadataConfig.SongTitleGroup}>.+)")

  /** A counter for generating unique names. */
  private val counter = new AtomicInteger

  /** A map with test metadata exclusions used by test cases. */
  private val MetaExclusions: Map[String, MetadataExclusion] = createExclusions()

  /**
    * A data class storing the dynamic parameters passed to a newly created
    * source check actor instance.
    *
    * @param source           the radio source
    * @param namePrefix       the name prefix
    * @param metadataConfig   the current metadata configuration
    * @param currentExclusion the current metadata exclusion
    * @param probe            the test probe monitoring the new instance
    */
  private case class SourceCheckCreation(source: RadioSource,
                                         namePrefix: String,
                                         metadataConfig: MetadataConfig,
                                         currentExclusion: MetadataExclusion,
                                         probe: TestProbe[MetadataStateActor.SourceCheckCommand])

  private type PublishFunc = String => Unit

  /**
    * A special [[Source]] implementation that allows pushing data dynamically
    * via a function. This is used to simulate the stream with metadata. It
    * gives full control when data flows through the stream. Note that there is
    * no sync with downstream; if data is past faster than it can be consumed,
    * the last data item overrides the one before. The source can be completed
    * by passing an empty string to the function.
    *
    * The function to publish data is created by the class when the stream is
    * set up. Since this happens asynchronously, a [[Promise]] is used to
    * transfer it to clients. Clients must then wait for the promise to be
    * completed before they can publish data.
    *
    * @param promisePublishFunc the [[Promise]] to obtain the function to
    *                           publish data items
    */
  private class PublishSource(promisePublishFunc: Promise[PublishFunc])
    extends GraphStage[SourceShape[ByteString]]:
    private val out = Outlet[ByteString]("publishSource.out")

    override def shape: SourceShape[ByteString] = SourceShape(out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape):
        /** A callback for injecting a new data item. */
        private val dataCallback = getAsyncCallback[String](injectData)

        /** Stores the last injected data item. */
        private var optPendingData: Option[String] = None

        promisePublishFunc.success(dataCallback.invoke)

        setHandler(out, new OutHandler:
          override def onPull(): Unit =
            optPendingData foreach pushData
            optPendingData = None
        )

        /**
          * Allows publishing a new data item to this source.
          *
          * @param data the item to publish
          */
        def publish(data: String): Unit = dataCallback.invoke(data)

        /**
          * Pushes the given data item downstream.
          *
          * @param data the data to push
          */
        private def pushData(data: String): Unit =
          push(out, ByteString(data))

        /**
          * Handles a new data item that was published to this source.
          *
          * @param data the new data item
          */
        private def injectData(data: String): Unit =
          if data.isEmpty then
            completeStage()
          else if isAvailable(out) then
            pushData(data)
          else
            optPendingData = Some(data)
  end PublishSource

  /**
    * Generates a string of test metadata based on the given index.
    *
    * @param index the index
    * @return the test metadata with this index
    */
  private def metadata(index: Int): String = s"Metadata_$index"

  /**
    * Returns a [[Clock]] instance that generates an [[Instant]] incremented by
    * one second on each invocation.
    *
    * @return the clock ticking by seconds
    */
  private def tickSecondsClock(): Clock =
    val counter = new AtomicLong
    new Clock:
      override def getZone: ZoneId = ZoneId.systemDefault()

      override def withZone(zone: ZoneId): Clock =
        throw new UnsupportedOperationException()

      override def instant(): Instant = RefInstant.plusSeconds(TickSeconds * counter.incrementAndGet())

  /**
    * Returns the time that corresponds to the given number of ticks of the
    * second ticking clock.
    *
    * @param ticks the number of ticks
    * @return the corresponding time
    */
  private def timeForTicks(ticks: Int): LocalDateTime = RefTime.plusSeconds(ticks * TickSeconds)

  /**
    * Creates a number of metadata exclusions to be checked during test cases.
    *
    * @return the exclusions for the test radio source
    */
  private def createExclusions(): Map[String, MetadataExclusion] =
    val exBadMusic = MetadataConfig.MetadataExclusion(pattern = Pattern.compile(s".*$MetaBadMusic.*"),
      resumeMode = ResumeMode.NextSong, checkInterval = 1.minute, matchContext = MatchContext.Raw,
      applicableAt = Seq.empty, name = None)
    val exNotWanted = MetadataConfig.MetadataExclusion(pattern = Pattern.compile(s".*$MetaNotWanted.*"),
      matchContext = MatchContext.Raw, resumeMode = ResumeMode.MetadataChange, checkInterval = 2.minutes,
      applicableAt = Seq.empty, name = None)
    Map(MetaBadMusic -> exBadMusic, MetaNotWanted -> exNotWanted)

  /**
    * Creates a test [[MetadataConfig]] based on a mock.
    *
    * @param srcConfig the config to return for the test radio source
    * @return the test metadata config
    */
  private def createMetadataConfig(srcConfig: RadioSourceMetadataConfig): MetadataConfig =
    val config = Mockito.mock(classOf[MetadataConfig])
    when(config.exclusions).thenReturn(Seq.empty)
    when(config.metadataSourceConfig(TestRadioSource)).thenReturn(srcConfig)
    config

  /**
    * Initializes a mock for a [[MetadataConfig]] instance to return the test
    * metadata exclusions. One is treated as global exclusion, the other one is
    * returned as part of the metadata configuration of the test radio source.
    *
    * @param configMock the mock to be initialized
    * @return the initialized mock
    */
  private def initMetaConfigMock(configMock: MetadataConfig): MetadataConfig =
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData),
      exclusions = Seq(MetaExclusions(MetaNotWanted)))
    when(configMock.exclusions).thenReturn(Seq(MetaExclusions(MetaBadMusic)))
    when(configMock.metadataSourceConfig(any())).thenReturn(MetadataConfig.EmptySourceConfig)
    when(configMock.metadataSourceConfig(TestRadioSource)).thenReturn(sourceConfig)
    configMock

  /**
    * Sets up a source for metadata strings that allows passing data 
    * dynamically. Clients can simply call a function to publish a new metadata
    * string to the stream.
    *
    * @return a tuple with the function that allows publishing data and the
    *         [[Source]] for the metadata stream
    */
  private def publishMetadataSource(): (PublishFunc, Source[ByteString, NotUsed]) =
    val publishPromise = Promise[PublishFunc]()
    val source = Source.fromGraph(new PublishSource(publishPromise))

    val pubFunc: PublishFunc = data =>
      // The Await call is bad, but in a test it should be acceptable.
      val sourcePubFunc = Await.result(publishPromise.future, 3.seconds)
      sourcePubFunc(data)
    (pubFunc, source)
end MetadataStateActorSpec

/**
  * Test class for [[MetadataStateActor]].
  */
class MetadataStateActorSpec extends AnyFlatSpec with Matchers with ActorTestKitSupport with MockitoSugar:

  import MetadataStateActorSpec.*
  
  "Check runner actor" should "send a result if metadata has changed" in :
    val nextMetadata = "No problem here"
    val helper = new RunnerTestHelper(MetaNotWanted)
    val (publish, source) = publishMetadataSource()
    val handle = helper.createHandleMock(source)

    helper.prepareFinderService(nextMetadata, timeForTicks(1))
      .expectAndAnswerHandleRequest(Success(handle))
    publish(nextMetadata)

    helper.expectCheckResult()
      .expectHandleReleased(handle, Some(nextMetadata))

  it should "stop itself after sending a result" in :
    val nextMetadata = "ok"
    val helper = new RunnerTestHelper(MetaNotWanted)
    val (publish, source) = publishMetadataSource()
    val handle = helper.createHandleMock(source)

    helper.prepareFinderService(nextMetadata, timeForTicks(1))
      .expectAndAnswerHandleRequest(Success(handle))
    publish(nextMetadata)
    helper.expectCheckResult()
      .checkActorStopped()

  it should "continue the check if a change in metadata is not sufficient" in :
    val refTime = timeForTicks(1)
    val insufficientMetadata = "Ok, but no title"
    val goodMetadata = "StreamTitle='good / music';"
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(insufficientMetadata, refTime)
      .prepareFinderService(goodMetadata, timeForTicks(2))
      .prepareIntervalsService(refTime, Before(new LazyDate(refTime.plusMinutes(1))))
      .expectAndAnswerHandleRequest(Success(handle))
      .publishInsufficientMetadata(publish, insufficientMetadata)

    publish(goodMetadata)

    helper.expectCheckResult()
      .expectHandleReleased(handle, Some(goodMetadata))

  it should "evaluate the resume interval correctly" in :
    val refTime = timeForTicks(1)
    val nextMetadata = "Ok, even without title"
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(nextMetadata, refTime)
      .prepareIntervalsService(refTime, Inside(new LazyDate(refTime), new LazyDate(refTime.plusMinutes(1))))
      .expectAndAnswerHandleRequest(Success(handle))
    publish(nextMetadata)

    helper.expectCheckResult()
      .expectHandleReleased(handle, Some(nextMetadata))
      .checkActorStopped()

  it should "handle an undefined pattern for extracting song information" in :
    val nextMetadata = "no title"
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic, optRegSongPattern = None)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(nextMetadata, timeForTicks(1))
      .expectAndAnswerHandleRequest(Success(handle))
    publish(nextMetadata)

    helper.expectCheckResult()
      .expectHandleReleased(handle, Some(nextMetadata))

  it should "store the latest interval query result" in :
    val refTime = timeForTicks(1)
    val refTime2 = timeForTicks(2)
    val nextMetadata1 = "Ok, but no title"
    val nextMetadata2 = "Ok, but still no title"
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(nextMetadata1, refTime)
      .prepareFinderService(nextMetadata2, refTime2)
      .prepareIntervalsService(refTime, Before(new LazyDate(refTime.plusMinutes(1))))
      .expectAndAnswerHandleRequest(Success(handle))
      .publishInsufficientMetadata(publish, nextMetadata1)
      .publishInsufficientMetadata(publish, nextMetadata2)

    verify(helper.intervalService, times(1)).evaluateIntervals(any(), any(), any())(any())

  it should "run another interval query if necessary" in :
    val refTime1 = timeForTicks(1)
    val refTime2 = timeForTicks(2)
    val nextMetadata1 = "Ok, but no title"
    val nextMetadata2 = "Ok, but still no title"
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(nextMetadata1, refTime1)
      .prepareFinderService(nextMetadata2, refTime2)
      .prepareIntervalsService(refTime1, Before(new LazyDate(refTime2.minusSeconds(2))))
      .prepareIntervalsService(refTime2, Before(new LazyDate(refTime2.plusMinutes(1))))
      .expectAndAnswerHandleRequest(Success(handle))
      .publishInsufficientMetadata(publish, nextMetadata1)
      .publishInsufficientMetadata(publish, nextMetadata2)

    verify(helper.intervalService, times(2)).evaluateIntervals(any(), any(), any())(any())

  it should "update the current metadata exclusion if it changes" in :
    val refTime1 = timeForTicks(1)
    val refTime2 = timeForTicks(2)
    val notWantedMetadata = "don't like it"
    val goodMetadata = "Ok, even if no title"
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(notWantedMetadata, refTime1, Some(MetaExclusions(MetaNotWanted)))
      .prepareFinderService(goodMetadata, refTime2)
      .expectAndAnswerHandleRequest(Success(handle))
      .publishInsufficientMetadata(publish, notWantedMetadata)

    publish(goodMetadata)
    helper.expectCheckResult()

  it should "handle an unexpectedly stopped radio stream" in :
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.expectAndAnswerHandleRequest(Success(handle))
    publish("")

    helper.expectCheckResult()
      .expectHandleReleased(handle, None)

  it should "handle a timeout command" in :
    val refTime = timeForTicks(1)
    val refTime2 = timeForTicks(2)
    val (publish, metaSource) = publishMetadataSource()
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(metaSource)

    helper.prepareFinderService(MetaBadMusic, refTime, Some(MetaExclusions(MetaBadMusic)))
      .prepareFinderService(MetaNotWanted, refTime2, Some(MetaExclusions(MetaNotWanted)))
      .prepareIntervalsService(refTime, Before(new LazyDate(refTime2.minusSeconds(1))))
      .prepareIntervalsService(refTime2, Before(new LazyDate(refTime2.plusSeconds(40))))
      .expectAndAnswerHandleRequest(Success(handle))
      .publishInsufficientMetadata(publish, MetaBadMusic)
      .publishInsufficientMetadata(publish, MetaNotWanted)
      .sendCommand(MetadataStateActor.MetadataCheckRunnerTimeout)
      .expectCheckResult(Some(MetaNotWanted))
      .expectHandleReleased(handle, Some(MetaNotWanted))
      .checkActorStopped()

  it should "handle a timeout before retrieving the stream handle" in :
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(Source.empty)

    helper.sendCommand(MetadataStateActor.MetadataCheckRunnerTimeout)
      .expectAndAnswerHandleRequest(Success(handle))
      .expectCheckResult()
      .expectHandleReleased(handle, None)
      .checkActorStopped()

  it should "handle a failed handle request" in :
    val exception = new IllegalStateException("Test exception: Cannot obtain stream handle.")
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.expectAndAnswerHandleRequest(Failure(exception))
      .expectCheckResult()
      .checkActorStopped()

  it should "handle a failed operation to attach to the handle" in :
    val exception = new IllegalStateException("Test exception: Cannot attach to stream handle.")
    val helper = new RunnerTestHelper(MetaBadMusic)
    val handle = helper.createHandleMock(Future.failed(exception))

    helper.expectAndAnswerHandleRequest(Success(handle))
      .expectCheckResult()
      .checkActorStopped()

  /**
    * Prepares a mock intervals service to return a specific result.
    *
    * @param service the mock service
    * @param queries the queries passed to the mock
    * @param time    the reference time
    * @param result  the result to return
    * @return the mock intervals service
    */
  private def initIntervalsServiceResult(service: EvaluateIntervalsService,
                                         queries: Seq[IntervalQuery],
                                         time: LocalDateTime,
                                         result: IntervalQueryResult): EvaluateIntervalsService =
    given ExecutionContext = any()

    when(service.evaluateIntervals(eqArg(queries), eqArg(time), any()))
      .thenAnswer((invocation: InvocationOnMock) =>
        val seqNo = invocation.getArgument[Int](2)
        val response = EvaluateIntervalsService.EvaluateIntervalsResponse(result, seqNo)
        Future.successful(response))
    service

  /**
    * A test helper class for testing metadata check runner actors.
    *
    * @param currentExclusionStr key for the current exclusion
    * @param optRegSongPattern   the pattern for extracting song title data
    */
  private class RunnerTestHelper(currentExclusionStr: String,
                                 optRegSongPattern: Option[Pattern] = Some(RegSongData)):
    /** A test configuration for the affected radio source. */
    private val metadataSourceConfig = RadioSourceMetadataConfig(resumeIntervals = Seq(mock),
      optSongPattern = optRegSongPattern,
      exclusions = MetaExclusions.values.toSeq)

    /** The name prefix used by the test actor. */
    private val namePrefix = "checker" + counter.incrementAndGet()

    /** A test metadata configuration. */
    private val metaConfig = createMetadataConfig(metadataSourceConfig)

    /** A test clock. */
    private def testClock = tickSecondsClock()

    /** Mock for the evaluate intervals service. */
    val intervalService: EvaluateIntervalsService = mock[EvaluateIntervalsService]

    /** Mock for the exclusions finder service. */
    private val finderService = mock[MetadataExclusionFinderService]

    /** Test probe for the stream handle manger actor. */
    private val probeHandleManager =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

    /** Test probe for the parent source checker actor. */
    private val probeSourceChecker = testKit.createTestProbe[MetadataStateActor.SourceCheckCommand]()

    /** The actor to be tested. */
    private val checkRunnerActor = createCheckRunnerActor()

    /**
      * Sends a command to the actor under test.
      *
      * @param command the command to be sent
      * @return this test helper
      */
    def sendCommand(command: MetadataStateActor.MetadataCheckRunnerCommand): RunnerTestHelper =
      checkRunnerActor ! command
      this

    /**
      * Expects that a check result with the given optional exclusion is sent
      * to the source check actor.
      *
      * @param optExclusionName the optional exclusion name
      * @return this test helper
      */
    def expectCheckResult(optExclusionName: Option[String] = None): RunnerTestHelper =
      val optExclusion = optExclusionName map (MetaExclusions(_))
      probeSourceChecker.expectMessage(MetadataStateActor.MetadataCheckResult(optExclusion))
      this

    /**
      * Expects that no message has been sent to the source check actor.
      *
      * @return this test helper
      */
    def expectNoCheckResult(): RunnerTestHelper =
      probeSourceChecker.expectNoMessage(200.millis)
      this

    /**
      * Prepares the mock for the intervals service to expect an invocation and
      * return a specific result.
      *
      * @param time   the reference time to be passed to the service
      * @param result the result to return
      * @return this test helper
      */
    def prepareIntervalsService(time: LocalDateTime, result: IntervalQueryResult): RunnerTestHelper =
      initIntervalsServiceResult(intervalService, metadataSourceConfig.resumeIntervals, time, result)
      this

    /**
      * Prepares the mock for the finder service to inspect an invocation and
      * return a specific result.
      *
      * @param metadata the expected metadata string passed in
      * @param refTime  the expected reference time
      * @param result   the result to return
      * @return this test helper
      */
    def prepareFinderService(metadata: String,
                             refTime: LocalDateTime,
                             result: Option[MetadataExclusion] = None): RunnerTestHelper =
      when(finderService.findMetadataExclusion(eqArg(metaConfig),
        eqArg(metadataSourceConfig),
        eqArg(CurrentMetadata(metadata)),
        eqArg(refTime),
        any())(any()))
        .thenAnswer((invocation: InvocationOnMock) =>
          val seqNo = invocation.getArgument[Int](4)
          val response = MetadataExclusionFinderService.MetadataExclusionFinderResponse(result, seqNo)
          Future.successful(response))
      this

    /**
      * Tests that the actor under test has stopped itself.
      *
      * @return this test helper
      */
    def checkActorStopped(): RunnerTestHelper =
      val probe = testKit.createDeadLetterProbe()
      probe.expectTerminated(checkRunnerActor)
      this

    /**
      * Expects a request for a radio stream handle and answers it with a 
      * response containing the given [[Try]] with a handle.
      *
      * @param triedHandle the tried handle for the response
      * @return this test helper
      */
    def expectAndAnswerHandleRequest(triedHandle: Try[RadioStreamHandle]): RunnerTestHelper =
      val handleRequest = probeHandleManager.expectMessageType[RadioStreamHandleManagerActor.GetStreamHandle]
      handleRequest.params.streamSource should be(TestRadioSource)
      handleRequest.params.streamName should be(namePrefix + "_metadataCheck")
      val handleResult = RadioStreamHandleManagerActor.GetStreamHandleResponse(
        TestRadioSource,
        triedHandle,
        None
      )
      handleRequest.replyTo ! handleResult
      this

    /**
      * Expects that a stream handle is released to the handle manager actor.
      *
      * @param handle      the handle
      * @param optMetadata optional metadata for the handle
      * @return this test helper
      */
    def expectHandleReleased(handle: RadioStreamHandle, optMetadata: Option[String]): RunnerTestHelper =
      val release = probeHandleManager.expectMessageType[RadioStreamHandleManagerActor.ReleaseStreamHandle]
      release.source should be(TestRadioSource)
      release.streamHandle should be(handle)
      release.optLastMetadata should be(optMetadata.map(CurrentMetadata.apply))
      this

    /**
      * Creates a mock for a stream handle that is prepared for an operation to
      * attach to the metadata stream yielding the given result.
      *
      * @param attachResult the result of the operation
      * @return the mock for the stream handle
      */
    def createHandleMock(attachResult: Future[Source[ByteString, NotUsed]]): RadioStreamHandle =
      given classic.ActorSystem = any()

      val handle = mock[RadioStreamHandle]
      when(handle.attachMetadataSinkOrCancel(any())).thenReturn(attachResult)
      handle

    /**
      * Creates a mock for a stream handle that is prepared for an operation to
      * attach to the metadata stream yielding the given [[Source]].
      *
      * @param source the [[Source]] for the metadata stream
      * @return the mock for the stream handle
      */
    def createHandleMock(source: Source[ByteString, NotUsed]): RadioStreamHandle =
      createHandleMock(Future.successful(source))

    /**
      * Convenience function that sends new metadata through the radio stream
      * and expects that this does not yield a successful check.
      *
      * @param publish  the function for sending metadata
      * @param metadata the metadata to send
      * @return this test helper
      */
    def publishInsufficientMetadata(publish: PublishFunc, metadata: String): RunnerTestHelper =
      publish(metadata)
      expectNoCheckResult()

    /**
      * Creates the test actor instance.
      *
      * @return the actor to be tested
      */
    private def createCheckRunnerActor(): ActorRef[MetadataStateActor.MetadataCheckRunnerCommand] =
      val behavior = MetadataStateActor.checkRunnerBehavior(TestRadioSource,
        namePrefix,
        metaConfig,
        MetaExclusions(currentExclusionStr),
        probeHandleManager.ref,
        intervalService,
        finderService,
        probeSourceChecker.ref,
        testClock)
      testKit.spawn(behavior)

  "Source check actor" should "schedule the initial check" in :
    val expDelay = MetaExclusions(MetaBadMusic).checkInterval
    val helper = new SourceCheckTestHelper

    val schedule = helper.expectScheduledInvocation()

    schedule.delay should be(expDelay)

  it should "support canceling the current check" in :
    val helper = new SourceCheckTestHelper

    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      .expectCheckRunnerTimeout()
      .checkActorNotStopped:
        helper.sendCommand(MetadataStateActor.MetadataCheckResult(None))

  it should "support canceling the current check if no check is ongoing" in :
    val helper = new SourceCheckTestHelper

    helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      .checkActorNotStopped:
        helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))

  it should "support canceling the current check and stopping itself if no check is ongoing" in :
    val helper = new SourceCheckTestHelper

    helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = true))
      .checkActorStopped()

  it should "support canceling the current check and stopping itself after cancellation is complete" in :
    val helper = new SourceCheckTestHelper

    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .sendCommand(MetadataStateActor.CancelSourceCheck(terminate = true))
      .checkActorNotStopped:
        helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      .sendCommand(MetadataStateActor.MetadataCheckResult(optExclusion = None))
      .checkActorStopped()

  it should "schedule a timeout for a new check" in :
    val helper = new SourceCheckTestHelper
    helper.expectAndTriggerScheduledInvocation()

    val schedule = helper.expectScheduledInvocation()

    schedule.delay should be(TestRadioConfig.metadataCheckTimeout)
    schedule.invocation.send()
    helper.expectSourceCheckTimeout()

  it should "handle a successful check result" in :
    val helper = new SourceCheckTestHelper

    helper.expectAndTriggerScheduledInvocation()
      .sendCommand(MetadataStateActor.MetadataCheckResult(None))
      .expectStateCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .checkActorStopped()

  it should "handle a check result requiring another check" in :
    val helper = new SourceCheckTestHelper
    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .prepareIntervalsService(timeForTicks(2), IntervalTypes.After { time => time })
      .expectScheduledInvocation() // Scheduled timeout

    helper.sendCommand(MetadataStateActor.MetadataCheckResult(MetaExclusions.get(MetaNotWanted)))
      .expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaNotWanted, 2)

  /**
    * Helper function for testing whether further checks are scheduled with
    * corrects delays.
    *
    * @param queryResult the result of the interval service
    * @param expDelay    the expected delay
    */
  private def checkDelayOfNextSchedule(queryResult: IntervalQueryResult, expDelay: FiniteDuration): Unit =
    val helper = new SourceCheckTestHelper
    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .prepareIntervalsService(timeForTicks(2), queryResult)
      .expectScheduledInvocation() // Scheduled timeout

    val invocation = helper.sendCommand(MetadataStateActor.MetadataCheckResult(MetaExclusions.get(MetaNotWanted)))
      .expectScheduledInvocation()

    val delta = expDelay - invocation.delay
    delta should be < 3.seconds

  it should "schedule a follow-up check based on the current exclusion" in :
    val queryResult = Before(new LazyDate(timeForTicks(1).plusMinutes(5)))
    val expDelay = MetaExclusions(MetaNotWanted).checkInterval

    checkDelayOfNextSchedule(queryResult, expDelay)

  it should "schedule a follow-up check based on the next resume interval" in :
    val queryResult = Before(new LazyDate(timeForTicks(2).plusSeconds(10)))
    val expDelay = 10.seconds

    checkDelayOfNextSchedule(queryResult, expDelay)

  it should "handle a large temporal delta gracefully when scheduling a follow-up check" in :
    val queryResult = Before(new LazyDate(timeForTicks(1).plusYears(5000)))
    val expDelay = MetaExclusions(MetaNotWanted).checkInterval

    checkDelayOfNextSchedule(queryResult, expDelay)

  /**
    * A test helper class for the source check actor.
    */
  private class SourceCheckTestHelper:
    /** Test probe for the scheduler actor. */
    private val probeScheduler = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the metadata state actor. */
    private val probeStateActor = testKit.createTestProbe[MetadataStateActor.MetadataExclusionStateCommand]()

    /** A queue to wait for the creation of check runner actors. */
    private val queueCheckerCreation =
      new LinkedBlockingQueue[TestProbe[MetadataStateActor.MetadataCheckRunnerCommand]]

    /**
      * Holds the test probe for the current check runner actor. This reference
      * is set dynamically when the check runner factory is invoked.
      */
    private val refProbeChecker = new AtomicReference[TestProbe[MetadataStateActor.MetadataCheckRunnerCommand]]

    /** Stores the current exclusion passed to the check runner. */
    private val refCurrentExclusion = new AtomicReference[MetadataExclusion]

    /** Stores the name prefix passed to the check runner. */
    private val refNamePrefix = new AtomicReference[String]

    /** The test configuration for the current radio source. */
    private val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData),
      resumeIntervals = Seq(mock),
      exclusions = MetaExclusions.values.toSeq)

    /** A test metadata configuration. */
    private val metaConfig = createMetadataConfig(sourceConfig)

    /** The clock to be passed to the retriever actor. */
    private val clock = tickSecondsClock()

    /** Test probe for the stream handle manager actor. */
    private val probeHandleManager =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

    /** Mock for the evaluate intervals service. */
    private val intervalService = createIntervalsService()

    /** Mock for the exclusion finder service. */
    private val finderService = mock[MetadataExclusionFinderService]

    /** The actor to be tested. */
    private val sourceCheckActor = createSourceCheckerActor()

    /**
      * Sends the given command to the source check actor.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: MetadataStateActor.SourceCheckCommand): SourceCheckTestHelper =
      sourceCheckActor ! command
      this

    /**
      * Prepares the mock for the intervals service to expect an invocation and
      * return a specific result.
      *
      * @param time   the reference time to be passed to the service
      * @param result the result to return
      * @return this test helper
      */
    def prepareIntervalsService(time: LocalDateTime, result: IntervalQueryResult): SourceCheckTestHelper =
      initIntervalsServiceResult(intervalService, sourceConfig.resumeIntervals, time, result)
      this

    /**
      * Expects an invocation of the scheduler actor and returns the
      * corresponding command.
      *
      * @return the command passed to the scheduler actor
      */
    def expectScheduledInvocation(): ScheduledInvocationActor.ActorInvocationCommand =
      probeScheduler.expectMessageType[ScheduledInvocationActor.ActorInvocationCommand]

    /**
      * Expects an invocation of the scheduler actor and simulates the
      * scheduled invocation immediately. Note: The delay is not checked,.
      *
      * @return this test helper
      */
    def expectAndTriggerScheduledInvocation(): SourceCheckTestHelper =
      val invocationCommand = expectScheduledInvocation()
      invocationCommand.invocation.send()
      this

    /**
      * Expects that a timeout command has been sent to the check runner.
      *
      * @return this test helper
      */
    def expectCheckRunnerTimeout(): SourceCheckTestHelper =
      probeCheckRunner.expectMessage(MetadataStateActor.MetadataCheckRunnerTimeout)
      this

    /**
      * Expects the creation of another check runner instance and stores it
      * internally, so that it can be accessed.
      *
      * @param expExclusion the key for the expected current exclusion
      * @param checkIndex   the numeric index expected in the name prefix
      * @return this test helper
      */
    def expectCheckRunnerCreation(expExclusion: String, checkIndex: Int = 1): SourceCheckTestHelper =
      refProbeChecker set queueCheckerCreation.poll(3, TimeUnit.SECONDS)
      refCurrentExclusion.get() should be(MetaExclusions(expExclusion))
      refNamePrefix.get() should be("testNamePrefix_run" + checkIndex)
      this

    /**
      * Tests that the actor under test has terminated.
      *
      * @return this test helper
      */
    def checkActorStopped(): SourceCheckTestHelper =
      val probe = testKit.createDeadLetterProbe()
      probe.expectTerminated(sourceCheckActor)
      this

    /**
      * Tests that the actor under test has not terminated. This can be checked
      * indirectly only. The function runs the provided block which should
      * somehow interact with the test actor. It then checks that no dead
      * letter message was received.
      *
      * @param block the block to trigger the test actor
      * @return this test helper
      */
    def checkActorNotStopped(block: => Unit): SourceCheckTestHelper =
      val probe = testKit.createDeadLetterProbe()
      block
      probe.expectNoMessage(200.millis)
      this

    /**
      * Expects that the given command was sent to the parent state actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectStateCommand(command: MetadataStateActor.MetadataExclusionStateCommand): SourceCheckTestHelper =
      probeStateActor.expectMessage(command)
      this

    /**
      * Expects that a source check timeout command has been sent to the parent
      * state actor.
      *
      * @return this test helper
      */
    def expectSourceCheckTimeout(): SourceCheckTestHelper =
      expectStateCommand(MetadataStateActor.SourceCheckTimeout(TestRadioSource, sourceCheckActor))

    /**
      * Returns the probe for the check runner actor. This needs to be obtained
      * from a reference, since it is created dynamically.
      *
      * @return the probe for the check runner actor
      */
    private def probeCheckRunner: TestProbe[MetadataStateActor.MetadataCheckRunnerCommand] =
      val probe = refProbeChecker.get()
      probe should not be null
      probe

    /**
      * Creates the mock for the intervals service and prepares it to answer
      * the first invocation.
      *
      * @return the mock intervals service
      */
    private def createIntervalsService(): EvaluateIntervalsService =
      initIntervalsServiceResult(mock[EvaluateIntervalsService], sourceConfig.resumeIntervals, timeForTicks(1),
        Inside(new LazyDate(RefTime), new LazyDate(timeForTicks(1).plusMinutes(2))))

    /**
      * Creates a factory for a check runner behavior that checks the passed in
      * parameters and returns a behavior based on a test probe.
      *
      * @return the factory for a check runner behavior
      */
    private def createCheckRunnerFactory(): MetadataStateActor.MetadataCheckRunnerFactory =
      (source: RadioSource,
       namePrefix: String,
       metadataConfig: MetadataConfig,
       currentExclusion: MetadataExclusion,
       handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
       intervalServiceParam: EvaluateIntervalsService,
       finderServiceParam: MetadataExclusionFinderService,
       sourceChecker: ActorRef[MetadataStateActor.SourceCheckCommand],
       c: Clock) => {
        source should be(TestRadioSource)
        metadataConfig should be(metaConfig)
        handleManager should be(probeHandleManager.ref)
        intervalServiceParam should be(intervalService)
        finderServiceParam should be(finderService)
        sourceChecker should be(sourceCheckActor)
        c should be(clock)

        refCurrentExclusion set currentExclusion
        refNamePrefix set namePrefix
        val probeChecker = testKit.createTestProbe[MetadataStateActor.MetadataCheckRunnerCommand]()
        queueCheckerCreation offer probeChecker
        Behaviors.monitor(probeChecker.ref, Behaviors.ignore)
      }

    /**
      * Creates the actor to be tested.
      *
      * @return the actor under test
      */
    private def createSourceCheckerActor(): ActorRef[MetadataStateActor.SourceCheckCommand] =
      val behavior = MetadataStateActor.sourceCheckBehavior(TestRadioSource,
        "testNamePrefix",
        TestRadioConfig,
        metaConfig,
        MetaExclusions(MetaBadMusic),
        probeStateActor.ref,
        probeScheduler.ref,
        clock,
        probeHandleManager.ref,
        intervalService,
        finderService,
        createCheckRunnerFactory())
      testKit.spawn(behavior)

  /**
    * Checks whether the metadata state actor detects the given metadata
    * exclusion.
    *
    * @param exclusion the exclusion to check
    */
  private def checkMetadataExclusion(exclusion: String): Unit =
    val helper = new MetadataStateTestHelper

    helper.sendMetadataEvent(exclusion)
      .expectDisabledSource()

    val creation = helper.nextSourceCheckCreation()
    creation.currentExclusion should be(MetaExclusions(exclusion))
    creation.source should be(TestRadioSource)
    creation.namePrefix should be(s"${MetadataStateActor.SourceCheckActorNamePrefix}1")

  "Metadata exclusion state actor" should "detect a global metadata exclusion" in :
    checkMetadataExclusion(MetaBadMusic)

  it should "detect a metadata exclusion for the current source" in :
    checkMetadataExclusion(MetaNotWanted)

  /**
    * Tests whether a radio event unrelated to metadata updates is ignored.
    *
    * @param event the event
    */
  private def checkIrrelevantEvent(event: RadioEvent): Unit =
    val helper = new MetadataStateTestHelper

    helper.sendEvent(event)
      .expectNoSourceCheckCreation()
      .sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()

  it should "ignore metadata events without current metadata" in :
    checkIrrelevantEvent(RadioMetadataEvent(TestRadioSource, MetadataNotSupported))

  it should "ignore events of other types" in :
    checkIrrelevantEvent(RadioSourceChangedEvent(TestRadioSource))

  it should "ignore metadata not matched by an exclusion" in :
    val helper = new MetadataStateTestHelper

    helper.sendMetadataEvent("no problem")
      .expectNoSourceCheckCreation()
      .sendMetadataEvent(MetaBadMusic, seqNo = 2)
      .nextSourceCheckCreation()

  it should "create different source check actors for different sources" in :
    val source2 = radioSource(2)
    val event2 = RadioMetadataEvent(source2, CurrentMetadata(MetaBadMusic))
    val helper = new MetadataStateTestHelper
    helper.prepareFinderService(MetaBadMusic, source2, seqNo = 2, time = event2.time)
    val creation1 = helper.sendMetadataEvent(MetaNotWanted)
      .nextSourceCheckCreation()

    val creation2 = helper.sendEvent(event2)
      .nextSourceCheckCreation()

    creation2.metadataConfig should be(creation1.metadataConfig)
    creation2.namePrefix should be(s"${MetadataStateActor.SourceCheckActorNamePrefix}2")

  it should "not create multiple check actors per source" in :
    val helper = new MetadataStateTestHelper
    helper.sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()

    helper.sendMetadataEvent(MetaNotWanted)
      .expectNoSourceCheckCreation()

  it should "handle a successful source check" in :
    val helper = new MetadataStateTestHelper
    helper.sendMetadataEvent(MetaNotWanted)
      .expectDisabledSource()
      .nextSourceCheckCreation()

    val creation = helper.sendCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .expectEnabledSource()
      .sendMetadataEvent(MetaBadMusic, seqNo = 2)
      .nextSourceCheckCreation()

    creation.source should be(TestRadioSource)

  it should "ignore a message about a successful source check if the source is not disabled" in :
    val helper = new MetadataStateTestHelper

    helper.sendCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .expectNoSourceEnabledChange()

  it should "forward a source check timeout message" in :
    val helper = new MetadataStateTestHelper
    val creation = helper.sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()

    helper.sendCommand(MetadataStateActor.SourceCheckTimeout(TestRadioSource, creation.probe.ref))

    creation.probe.expectMessage(MetadataStateActor.CancelSourceCheck(terminate = false))

  it should "ignore a source check timeout message if the source is not disabled" in :
    val helper = new MetadataStateTestHelper
    val creation = helper.sendMetadataEvent(MetaNotWanted)
      .nextSourceCheckCreation()

    helper.sendCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .sendCommand(MetadataStateActor.SourceCheckTimeout(TestRadioSource, creation.probe.ref))

    creation.probe.expectNoMessage(200.millis)

  it should "enable all sources if the metadata config changes" in :
    val SourceCount = 4
    val sources = (1 to SourceCount) map radioSource
    val nextConfig = initMetaConfigMock(mock[MetadataConfig])
    val helper = new MetadataStateTestHelper
    val creations = sources.zipWithIndex map { t =>
      val event = RadioMetadataEvent(t._1, CurrentMetadata(MetaBadMusic))
      helper.prepareFinderService(MetaBadMusic, t._1, seqNo = t._2 + 1, time = event.time)
        .sendEvent(event)
        .expectDisabledSource(t._1)
        .nextSourceCheckCreation()
    }

    helper.sendCommand(MetadataStateActor.InitMetadataConfig(nextConfig))

    creations foreach { creation =>
      creation.probe.expectMessage(MetadataStateActor.CancelSourceCheck(terminate = true))
    }
    val enabledSources = creations map { _ => helper.nextEnabledSource().source }
    enabledSources should contain theSameElementsAs sources

    val nextSource = radioSource(1)
    helper.prepareFinderService(MetaBadMusic, nextSource, nextConfig, seqNo = SourceCount + 1, time = RefTime)
    val nextCreation = helper.sendEvent(RadioMetadataEvent(nextSource, CurrentMetadata(MetaBadMusic), RefTime))
      .nextSourceCheckCreation()
    nextCreation.metadataConfig should be(nextConfig)
    nextCreation.namePrefix should be(s"${MetadataStateActor.SourceCheckActorNamePrefix}${SourceCount + 1}")

  it should "ignore stale results from the exclusion finder service" in :
    val metaStr = "This is not forbidden"
    val promiseResponse = Promise[MetadataExclusionFinderService.MetadataExclusionFinderResponse]()
    val helper = new MetadataStateTestHelper
    helper.prepareFinderService(MetaBadMusic, optResponse = Some(promiseResponse.future), time = RefTime)
      .sendEvent(RadioMetadataEvent(TestRadioSource, CurrentMetadata(MetaBadMusic), RefTime))
      .sendMetadataEvent(metaStr, seqNo = 2)

    val exclusion = Some(MetaExclusions(MetaBadMusic))
    promiseResponse.success(MetadataExclusionFinderService.MetadataExclusionFinderResponse(exclusion, 0))

    helper.expectNoSourceEnabledChange()

  /**
    * A helper class for testing the metadata state actor.
    */
  private class MetadataStateTestHelper:
    /** Test probe for the source enabled state actor. */
    private val probeEnabledStateActor = testKit.createTestProbe[RadioControlProtocol.SourceEnabledStateCommand]()

    /** Test probe for the scheduler actor. */
    private val probeSchedulerActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the event manager actor. */
    private val probeEventActor = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the stream manager actor. */
    private val probeHandleManagerActor =
      testKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

    /** A test clock instance. */
    private val clock = tickSecondsClock()

    /** Mock for the interval service. */
    private val intervalsService = mock[EvaluateIntervalsService]

    /** Mock for the exclusions finder service. */
    private val finderService = mock[MetadataExclusionFinderService]

    /** A queue to keep track on source check actor creations. */
    private val queueSourceCheckCreations = new LinkedBlockingQueue[SourceCheckCreation]

    /** Stores the listener registered at the event actor. */
    private val refEventListener = new AtomicReference[ActorRef[RadioEvent]]

    /** The test metadata configuration. */
    private val metaConfig = initMetaConfigMock(mock[MetadataConfig])

    /** The actor to be tested. */
    private val stateActor = createStateActor()

    /**
      * Sends the given command to the actor under test.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: MetadataStateActor.MetadataExclusionStateCommand): MetadataStateTestHelper =
      stateActor ! command
      this

    /**
      * Sends the given event to the registered listener.
      *
      * @param event the radio event to send
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): MetadataStateTestHelper =
      eventListener ! event
      this

    /**
      * Sends a metadata event with the given text to the registered listener.
      * Also prepares the finder service mock to return the corresponding
      * result.
      *
      * @param data  the metadata to send
      * @param seqNo the expected sequence number
      * @return this test helper
      */
    def sendMetadataEvent(data: String, seqNo: Int = 1): MetadataStateTestHelper =
      val event = RadioMetadataEvent(TestRadioSource, CurrentMetadata(data))
      prepareFinderService(data, seqNo = seqNo, time = event.time)
      sendEvent(event)

    /**
      * Prepares the mock for the finder service to expect an invocation and
      * return the corresponding exclusion.
      *
      * @param metadata    the metadata passed to the service
      * @param source      the affected radio source
      * @param config      the expected metadata config
      * @param time        the expected reference time
      * @param seqNo       the expected sequence number
      * @param optResponse the optional response to be returned
      * @return this test helper
      */
    def prepareFinderService(metadata: String,
                             source: RadioSource = TestRadioSource,
                             config: MetadataConfig = metaConfig,
                             time: LocalDateTime,
                             seqNo: Int = 1,
                             optResponse:
                             Option[Future[MetadataExclusionFinderService.MetadataExclusionFinderResponse]] = None):
    MetadataStateTestHelper =
      val response = optResponse.getOrElse(Future.successful(
        MetadataExclusionFinderService.MetadataExclusionFinderResponse(MetaExclusions.get(metadata), seqNo)))
      val sourceConfig = config.metadataSourceConfig(source)
      when(finderService.findMetadataExclusion(eqArg(config),
        eqArg(sourceConfig),
        eqArg(CurrentMetadata(metadata)),
        eqArg(time),
        eqArg(seqNo))(any())).thenReturn(response)
      this

    /**
      * Expects the creation of a source check actor and returns its reference.
      *
      * @return the next source check actor created by the test actor
      */
    def nextSourceCheckCreation(): SourceCheckCreation =
      val checkActor = queueSourceCheckCreations.poll(3, TimeUnit.SECONDS)
      checkActor should not be null
      checkActor

    /**
      * Expects that no source check actor is created.
      *
      * @return this test helper
      */
    def expectNoSourceCheckCreation(): MetadataStateTestHelper =
      queueSourceCheckCreations.poll(200, TimeUnit.MILLISECONDS) should be(null)
      this

    /**
      * Expects that the given radio source has been disabled.
      *
      * @param source the source in question
      * @return this test helper
      */
    def expectDisabledSource(source: RadioSource = TestRadioSource): MetadataStateTestHelper =
      probeEnabledStateActor.expectMessage(RadioControlProtocol.DisableSource(source))
      this

    /**
      * Expects that the given radio source has been enabled.
      *
      * @param source the source in question
      * @return this test helper
      */
    def expectEnabledSource(source: RadioSource = TestRadioSource): MetadataStateTestHelper =
      nextEnabledSource().source should be(source)
      this

    /**
      * Expects a message about an enabled source and returns it. This function
      * is needed, since the order in which multiple sources are enabled is not
      * deterministic.
      *
      * @return the next message about an enabled source
      */
    def nextEnabledSource(): RadioControlProtocol.EnableSource =
      probeEnabledStateActor.expectMessageType[RadioControlProtocol.EnableSource]

    /**
      * Expects that no message about an enabled or disabled source comes in.
      *
      * @return this test helper
      */
    def expectNoSourceEnabledChange(): MetadataStateTestHelper =
      probeEnabledStateActor.expectNoMessage(200.millis)
      this

    /**
      * Returns the event listener the actor under test should have registered
      * at the event manager actor. It is obtained on first access.
      *
      * @return the event listener
      */
    private def eventListener: ActorRef[RadioEvent] =
      if refEventListener.get() == null then
        val reg = probeEventActor.expectMessageType[EventManagerActor.RegisterListener[RadioEvent]]
        refEventListener set reg.listener
      refEventListener.get()

    /**
      * Creates a factory for creating new source check actors. This factory
      * implementation checks some of the parameters provided to it and
      * constructs a [[SourceCheckCreation]] object with relevant data. This
      * object can be obtained via a queue.
      *
      * @return the factory for source check actors
      */
    private def createSourceCheckerFactory(): MetadataStateActor.SourceCheckFactory =
      (source: RadioSource,
       namePrefix: String,
       radioConfig: RadioPlayerConfig,
       metadataConfig: MetadataConfig,
       currentExclusion: MetadataExclusion,
       stateActorParam: ActorRef[MetadataStateActor.MetadataExclusionStateCommand],
       scheduleActor: ActorRef[ScheduledInvocationActor.ScheduledInvocationCommand],
       clockParam: Clock,
       handleManager: ActorRef[RadioStreamHandleManagerActor.RadioStreamHandleCommand],
       intervalServiceParam: EvaluateIntervalsService,
       finderServiceParam: MetadataExclusionFinderService,
       _: MetadataStateActor.MetadataCheckRunnerFactory) => {
        radioConfig should be(TestRadioConfig)
        clockParam should be(clock)
        handleManager should be(probeHandleManagerActor.ref)
        intervalServiceParam should be(intervalsService)
        finderServiceParam should be(finderService)
        stateActorParam should be(stateActor)
        scheduleActor should be(probeSchedulerActor.ref)

        val probe = testKit.createTestProbe[MetadataStateActor.SourceCheckCommand]()
        val creation = SourceCheckCreation(source = source,
          namePrefix = namePrefix,
          metadataConfig = metadataConfig,
          currentExclusion = currentExclusion,
          probe = probe)
        queueSourceCheckCreations offer creation
        Behaviors.monitor(probe.ref, Behaviors.ignore)
      }

    /**
      * Creates a new instance of the state actor to be tested.
      *
      * @return the actor under test
      */
    private def createStateActor(): ActorRef[MetadataStateActor.MetadataExclusionStateCommand] =
      val behavior = MetadataStateActor.metadataStateBehavior(TestRadioConfig,
        probeEnabledStateActor.ref,
        probeSchedulerActor.ref,
        probeEventActor.ref,
        probeHandleManagerActor.ref,
        intervalsService,
        finderService,
        clock = clock,
        sourceCheckFactory = createSourceCheckerFactory())
      val actor = testKit.spawn(behavior)

      actor ! MetadataStateActor.InitMetadataConfig(metaConfig)
      actor
