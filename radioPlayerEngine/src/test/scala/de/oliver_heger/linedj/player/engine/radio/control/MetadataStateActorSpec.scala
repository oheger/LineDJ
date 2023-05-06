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

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.{TestProbe => ClassicTestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.player.engine.actors.{EventManagerActor, LocalBufferActor, PlaybackActor, ScheduledInvocationActor}
import de.oliver_heger.linedj.player.engine.interval.IntervalTypes.{Before, Inside, IntervalQuery, IntervalQueryResult}
import de.oliver_heger.linedj.player.engine.interval.{IntervalTypes, LazyDate}
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, MetadataExclusion, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.config.{MetadataConfig, RadioPlayerConfig}
import de.oliver_heger.linedj.player.engine.radio.control.RadioSourceConfigTestHelper.radioSource
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamManagerActor
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.PipedOutputStream
import java.time._
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.regex.Pattern
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object MetadataStateActorSpec {
  /** Constant for a reference instant. */
  private val RefInstant = Instant.parse("2023-03-31T20:37:16Z")

  /** The reference local time corresponding to the reference instant. */
  private val RefTime = LocalDateTime.ofInstant(RefInstant, ZoneOffset.UTC)

  /** Metadata to match an exclusion due to bad music. */
  private val MetaBadMusic = "Bad music"

  /** Metadata to match another exclusion. */
  private val MetaNotWanted = "skip this"

  /** A radio source used by tests. */
  private val TestRadioSource = RadioSource("sourceWithExcludedMetadata")

  /** A test audio source representing the resolved audio stream. */
  private val TestAudioSource = AudioSource("resolvedStreamURI", 0, 0, 0)

  /** A test audio player configuration object. */
  private val TestPlayerConfig = PlayerConfig(mediaManagerActor = null, actorCreator = null)

  /** A test radio player configuration. */
  private val TestRadioConfig = RadioPlayerConfig(playerConfig = TestPlayerConfig,
    metadataCheckTimeout = 99.seconds)

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
  private def tickSecondsClock(): Clock = {
    val counter = new AtomicLong
    new Clock {
      override def getZone: ZoneId = ZoneId.systemDefault()

      override def withZone(zone: ZoneId): Clock = super.withZone(zone)

      override def instant(): Instant = RefInstant.plusSeconds(counter.incrementAndGet())
    }
  }

  /**
    * Returns the time that corresponds to the given number of ticks of the
    * second ticking clock.
    *
    * @param ticks the number of ticks
    * @return the corresponding time
    */
  private def timeForTicks(ticks: Int): LocalDateTime = RefTime.plusSeconds(ticks)

  /**
    * Creates a number of metadata exclusions to be checked during test cases.
    *
    * @return the exclusions for the test radio source
    */
  private def createExclusions(): Map[String, MetadataExclusion] = {
    val exBadMusic = MetadataConfig.MetadataExclusion(pattern = Pattern.compile(s".*$MetaBadMusic.*"),
      resumeMode = ResumeMode.NextSong, checkInterval = 1.minute, matchContext = MatchContext.Raw, name = None)
    val exNotWanted = MetadataConfig.MetadataExclusion(pattern = Pattern.compile(s".*$MetaNotWanted.*"),
      matchContext = MatchContext.Raw, resumeMode = ResumeMode.MetadataChange, checkInterval = 2.minutes,
      name = None)
    Map(MetaBadMusic -> exBadMusic, MetaNotWanted -> exNotWanted)
  }

  /**
    * Creates a test [[MetadataConfig]] based on a mock.
    *
    * @param srcConfig the config to return for the test radio source
    * @return the test metadata config
    */
  private def createMetadataConfig(srcConfig: RadioSourceMetadataConfig): MetadataConfig = {
    val config = Mockito.mock(classOf[MetadataConfig])
    when(config.exclusions).thenReturn(Seq.empty)
    when(config.metadataSourceConfig(TestRadioSource)).thenReturn(srcConfig)
    config
  }

  /**
    * Initializes a mock for a [[MetadataConfig]] instance to return the test
    * metadata exclusions. One is treated as global exclusion, the other one is
    * returned as part of the metadata configuration of the test radio source.
    *
    * @param configMock the mock to be initialized
    * @return the initialized mock
    */
  private def initMetaConfigMock(configMock: MetadataConfig): MetadataConfig = {
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData),
      exclusions = Seq(MetaExclusions(MetaNotWanted)))
    when(configMock.exclusions).thenReturn(Seq(MetaExclusions(MetaBadMusic)))
    when(configMock.metadataSourceConfig(any())).thenReturn(MetadataConfig.EmptySourceConfig)
    when(configMock.metadataSourceConfig(TestRadioSource)).thenReturn(sourceConfig)
    configMock
  }
}

/**
  * Test class for [[MetadataStateActor]].
  */
class MetadataStateActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {

  import MetadataStateActorSpec._

  "Metadata retriever actor" should "send the latest metadata" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .sendMetadata(1)
      .sendRetrieverCommand(MetadataStateActor.GetMetadata)
      .checkRunnerCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(metadata(1)), RefTime))
  }

  it should "ignore other kinds of radio events" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .sendEvent(RadioMetadataEvent(TestRadioSource, MetadataNotSupported))
      .sendEvent(RadioSourceErrorEvent(TestRadioSource))
      .sendRetrieverCommand(MetadataStateActor.GetMetadata)
      .expectNoCheckRunnerCommand()
      .sendMetadata(1)
      .checkRunnerCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(metadata(1)), RefTime))
  }

  it should "reset the pending request flag after sending metadata" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .sendMetadata(1)
      .sendRetrieverCommand(MetadataStateActor.GetMetadata)
      .expectCheckRunnerCommand()

    helper.sendMetadata(2)
      .expectNoCheckRunnerCommand()
  }

  it should "only send metadata if it has changed" in {
    val time2 = LocalDateTime.of(2023, Month.MAY, 4, 22, 24, 27)
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .sendRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendMetadata(1)
      .checkRunnerCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(metadata(1)), RefTime))
      .sendMetadata(1)
      .sendRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendMetadata(2, time2)
      .checkRunnerCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(metadata(2)), time2))
  }

  it should "permanently request new audio data" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .expectAudioDataRequest()
      .answerAudioDataRequest()
      .expectAudioDataRequest()
  }

  it should "stop processing gracefully when receiving a CancelStream command" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .sendMetadata(1)
      .sendRetrieverCommand(MetadataStateActor.GetMetadata)
      .expectCheckRunnerCommand()

    helper.sendRetrieverCommand(MetadataStateActor.CancelStream)
      .expectAudioDataRequest()
      .answerAudioDataRequest()
      .expectStreamActorReleased(Some(CurrentMetadata(metadata(1))))
      .checkRunnerCommand(MetadataStateActor.RadioStreamStopped)
  }

  it should "handle a CancelStream command before receiving the stream actor" in {
    val helper = new RetrieverTestHelper

    helper.sendRetrieverCommand(MetadataStateActor.CancelStream)
      .passStreamActor()
      .expectNoAudioDataRequest()
      .expectStreamActorReleased(None)
      .checkRunnerCommand(MetadataStateActor.RadioStreamStopped)
  }

  it should "not release the stream actor before a pending data request is answered" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .sendRetrieverCommand(MetadataStateActor.CancelStream)
      .expectNoStreamActorRelease()
  }

  it should "handle a terminated stream actor" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .expectAudioDataRequest()
      .stopStreamActor()
      .checkRunnerCommand(MetadataStateActor.RadioStreamStopped)
      .expectNoStreamActorRelease()
  }

  it should "handle a terminated stream actor when waiting for cancellation" in {
    val helper = new RetrieverTestHelper

    helper.passStreamActor()
      .expectAudioDataRequest()
      .sendRetrieverCommand(MetadataStateActor.CancelStream)
      .stopStreamActor()
      .checkRunnerCommand(MetadataStateActor.RadioStreamStopped)
      .expectNoStreamActorRelease()
  }

  /**
    * Test helper class for testing the metadata retriever actor.
    */
  private class RetrieverTestHelper extends AutoCloseable {
    /** Test probe for the stream manager actor. */
    private val probeStreamManager = testKit.createTestProbe[RadioStreamManagerActor.RadioStreamManagerCommand]()

    /** Test probe for the check runner actor. */
    private val probeRunner = testKit.createTestProbe[MetadataStateActor.MetadataCheckRunnerCommand]()

    /** Test probe for the radio stream actor. */
    private val probeStreamActor = ClassicTestProbe()(system.classicSystem)

    /** Stores the event actor passed to the stream manager. */
    private var eventActor: ActorRef[RadioEvent] = _

    /** A stream to be used for the data source of the radio stream. */
    private val radioStream = new PipedOutputStream

    /** The retriever actor to be tested. */
    private val retrieverActor = createRetrieverActor()

    /**
      * @inheritdoc This implementation closes the simulated radio stream.
      */
    override def close(): Unit = {
      radioStream.close()
    }

    /**
      * Sends a metadata event with specific content to the registered event
      * listener actor.
      *
      * @param index the index of the metadata
      * @param time  the time of the event
      * @return this test helper
      */
    def sendMetadata(index: Int, time: LocalDateTime = RefTime): RetrieverTestHelper = {
      val event = RadioMetadataEvent(TestRadioSource, CurrentMetadata(metadata(index)), time)
      sendEvent(event)
    }

    /**
      * Sends the given event to the event actor registered at the stream
      * actor.
      *
      * @param event the event to send
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): RetrieverTestHelper = {
      eventActor should not be null
      eventActor ! event
      this
    }

    /**
      * Handles the interaction with the stream manager actor to pass the
      * requested stream actor to the actor under test.
      *
      * @return this test helper
      */
    def passStreamActor(): RetrieverTestHelper = {
      val request = probeStreamManager.expectMessageType[RadioStreamManagerActor.GetStreamActor]
      request.params.streamSource should be(TestRadioSource)
      eventActor = request.params.eventActor
      request.replyTo ! RadioStreamManagerActor.StreamActorResponse(TestRadioSource, probeStreamActor.ref)
      request.params.sourceListener(TestAudioSource, probeStreamActor.ref)
      this
    }

    /**
      * Sends the given command to the retriever actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def sendRetrieverCommand(command: MetadataStateActor.MetadataRetrieveCommand): RetrieverTestHelper = {
      retrieverActor ! command
      this
    }

    /**
      * Expects that a command has been sent to the check runner actor and
      * returns it.
      *
      * @return the command sent to the check runner actor
      */
    def expectCheckRunnerCommand(): MetadataStateActor.MetadataCheckRunnerCommand =
      probeRunner.expectMessageType[MetadataStateActor.MetadataCheckRunnerCommand]

    /**
      * Expects that the given command was sent to the check runner actor.
      *
      * @param expectedCommand the expected command
      * @return this test helper
      */
    def checkRunnerCommand(expectedCommand: MetadataStateActor.MetadataCheckRunnerCommand): RetrieverTestHelper = {
      expectCheckRunnerCommand() should be(expectedCommand)
      this
    }

    /**
      * Expects that no command was sent to the check runner actor.
      *
      * @return this test helper
      */
    def expectNoCheckRunnerCommand(): RetrieverTestHelper = {
      probeRunner.expectNoMessage(250.millis)
      this
    }

    /**
      * Simulates a failure of the radio stream actor by stopping it.
      *
      * @return this test helper
      */
    def stopStreamActor(): RetrieverTestHelper = {
      system.classicSystem stop probeStreamActor.ref
      this
    }

    /**
      * Expects that a request for audio data has been sent to the radio stream
      * actor.
      *
      * @return this test helper
      */
    def expectAudioDataRequest(): RetrieverTestHelper = {
      probeStreamActor.expectMsg(PlaybackActor.GetAudioData(4096))
      this
    }

    /**
      * Answers a request for audio data by sending a chunk of dummy data.
      *
      * @return this test helper
      */
    def answerAudioDataRequest(): RetrieverTestHelper = {
      val data = LocalBufferActor.BufferDataResult(ByteString(FileTestHelper.TestData))
      probeStreamActor.reply(data)
      this
    }

    /**
      * Expects that no request for audio data has been sent to the radio
      * stream actor.
      *
      * @return this test helper
      */
    def expectNoAudioDataRequest(): RetrieverTestHelper = {
      probeStreamActor.expectNoMessage(200.millis)
      this
    }

    /**
      * Expects that the radio stream actor has been released to the stream
      * manager actor.
      *
      * @param optMetadata the expected metadata in the release message
      * @return this test helper
      */
    def expectStreamActorReleased(optMetadata: Option[CurrentMetadata]): RetrieverTestHelper = {
      val releaseMsg = RadioStreamManagerActor.ReleaseStreamActor(TestRadioSource, probeStreamActor.ref,
        TestAudioSource, optMetadata)
      probeStreamManager.expectMessage(releaseMsg)
      this
    }

    /**
      * Expects that no release command has been sent to the stream manager
      * actor.
      *
      * @return this test helper
      */
    def expectNoStreamActorRelease(): RetrieverTestHelper = {
      probeStreamManager.expectNoMessage(200.millis)
      this
    }

    /**
      * Creates the metadata retriever actor to be tested.
      *
      * @return the retriever actor to be tested
      */
    private def createRetrieverActor(): ActorRef[MetadataStateActor.MetadataRetrieveCommand] =
      testKit.spawn(MetadataStateActor.retrieveMetadataBehavior(TestRadioSource,
        probeStreamManager.ref,
        probeRunner.ref))
  }

  "Check runner actor" should "send a result if metadata has changed" in {
    val nextMetadata = "No problem here"
    val helper = new RunnerTestHelper(MetaNotWanted)

    helper.prepareFinderService(nextMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata), LocalDateTime.now()))
      .expectRetrieverCommand(MetadataStateActor.CancelStream)
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .expectCheckResult()
  }

  it should "wait until the stream is canceled before sending the result" in {
    val nextMetadata = "ok"
    val helper = new RunnerTestHelper(MetaNotWanted)

    helper.prepareFinderService(nextMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata), LocalDateTime.now()))
      .expectNoCheckResult()
  }

  it should "stop itself after sending a result" in {
    val nextMetadata = "ok"
    val helper = new RunnerTestHelper(MetaNotWanted)

    helper.prepareFinderService(nextMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata), LocalDateTime.now()))
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .checkActorStopped()
  }

  it should "continue the check if a change in metadata is not sufficient" in {
    val refTime = LocalDateTime.of(2023, Month.APRIL, 7, 20, 26, 4)
    val insufficientMetadata = "Ok, but no title"
    val goodMetadata = "StreamTitle='good / music';"
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.prepareFinderService(insufficientMetadata)
      .prepareFinderService(goodMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .prepareIntervalsService(refTime, Before(new LazyDate(refTime.plusMinutes(1))))
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(insufficientMetadata), refTime))
      .expectNoCheckResult()
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(goodMetadata),
        refTime.plusSeconds(10)))
      .expectRetrieverCommand(MetadataStateActor.CancelStream)
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .expectCheckResult()
  }

  it should "evaluate the resume interval correctly" in {
    val refTime = LocalDateTime.of(2023, Month.APRIL, 9, 11, 52, 26)
    val nextMetadata = "Ok, even without title"
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.prepareFinderService(nextMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .prepareIntervalsService(refTime, Inside(new LazyDate(refTime.plusMinutes(1))))
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata), refTime))
      .expectRetrieverCommand(MetadataStateActor.CancelStream)
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .expectCheckResult()
      .checkActorStopped()
  }

  it should "handle an undefined pattern for extracting song information" in {
    val nextMetadata = "no title"
    val helper = new RunnerTestHelper(MetaBadMusic, optRegSongPattern = None)

    helper.prepareFinderService(nextMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata), LocalDateTime.now()))
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .expectCheckResult()
  }

  it should "store the latest interval query result" in {
    val refTime = LocalDateTime.of(2023, Month.APRIL, 7, 21, 23, 23)
    val nextMetadata1 = "Ok, but no title"
    val nextMetadata2 = "Ok, but still no title"
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.prepareFinderService(nextMetadata1)
      .prepareFinderService(nextMetadata2)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .prepareIntervalsService(refTime, Before(new LazyDate(refTime.plusMinutes(1))))
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata1), refTime))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata2),
        refTime.plusSeconds(10)))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
  }

  it should "run another interval query if necessary" in {
    val refTime1 = LocalDateTime.of(2023, Month.APRIL, 7, 21, 42, 16)
    val refTime2 = LocalDateTime.of(2023, Month.APRIL, 7, 21, 42, 46)
    val refTime3 = LocalDateTime.of(2023, Month.APRIL, 7, 21, 43, 50)
    val nextMetadata1 = "Ok, but no title"
    val nextMetadata2 = "Ok, but still no title"
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.prepareFinderService(nextMetadata1)
      .prepareFinderService(nextMetadata2)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .prepareIntervalsService(refTime1, Before(new LazyDate(refTime2)))
      .prepareIntervalsService(refTime3, Before(new LazyDate(refTime3.plusSeconds(10))))
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata1), refTime1))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(nextMetadata2), refTime3))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
  }

  it should "update the current metadata exclusion if it changes" in {
    val refTime = LocalDateTime.of(2023, Month.APRIL, 8, 18, 27, 23)
    val notWantedMetadata = "don't like it"
    val goodMetadata = "Ok, even if no title"
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.prepareFinderService(notWantedMetadata, Some(MetaExclusions(MetaNotWanted)))
      .prepareFinderService(goodMetadata)
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(notWantedMetadata), refTime))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(goodMetadata), refTime))
      .expectRetrieverCommand(MetadataStateActor.CancelStream)
  }

  it should "handle an unexpectedly stopped radio stream" in {
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .expectCheckResult()
  }

  it should "handle a timeout command" in {
    val refTime = LocalDateTime.of(2023, Month.APRIL, 8, 18, 52, 14)
    val refTime2 = LocalDateTime.of(2023, Month.APRIL, 8, 18, 54, 55)
    val helper = new RunnerTestHelper(MetaBadMusic)

    helper.prepareFinderService(MetaNotWanted, Some(MetaExclusions(MetaNotWanted)))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .prepareIntervalsService(refTime, Before(new LazyDate(refTime.plusSeconds(10))))
      .prepareIntervalsService(refTime2, Before(new LazyDate(refTime2.plusSeconds(40))))
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(MetaNotWanted), refTime))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataRetrieved(CurrentMetadata(MetaNotWanted), refTime2))
      .expectRetrieverCommand(MetadataStateActor.GetMetadata)
      .sendCommand(MetadataStateActor.MetadataCheckRunnerTimeout)
      .expectRetrieverCommand(MetadataStateActor.CancelStream)
      .sendCommand(MetadataStateActor.RadioStreamStopped)
      .expectCheckResult(Some(MetaNotWanted))
      .checkActorStopped()
  }

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
                                         result: IntervalQueryResult): EvaluateIntervalsService = {
    implicit val ec: ExecutionContext = testKit.system.executionContext
    val response = EvaluateIntervalsService.EvaluateIntervalsResponse(result, 0)
    when(service.evaluateIntervals(queries, time, 0)).thenReturn(Future.successful(response))
    service
  }

  /**
    * A test helper class for testing metadata check runner actors.
    *
    * @param currentExclusionStr key for the current exclusion
    * @param optRegSongPattern   the pattern for extracting song tile data
    */
  private class RunnerTestHelper(currentExclusionStr: String,
                                 optRegSongPattern: Option[Pattern] = Some(RegSongData)) {
    /** A test configuration for the affected radio source. */
    private val metadataSourceConfig = RadioSourceMetadataConfig(resumeIntervals = Seq(mock),
      optSongPattern = optRegSongPattern,
      exclusions = MetaExclusions.values.toSeq)

    /** A test metadata configuration. */
    private val metaConfig = createMetadataConfig(metadataSourceConfig)

    /** Mock for the evaluate intervals service. */
    private val intervalService = mock[EvaluateIntervalsService]

    /** Mock for the exclusions finder service. */
    private val finderService = mock[MetadataExclusionFinderService]

    /** Test probe for the stream manger actor. */
    private val probeStreamManager = testKit.createTestProbe[RadioStreamManagerActor.RadioStreamManagerCommand]()

    /** Test probe for the retriever actor. */
    private val probeRetriever = testKit.createTestProbe[MetadataStateActor.MetadataRetrieveCommand]()

    /** Test probe for the parent source checker actor. */
    private val probeSourceChecker = testKit.createTestProbe[MetadataStateActor.SourceCheckCommand]()

    /** Stores the reference to the check runner actor. */
    private val refCheckRunnerActor = new AtomicReference[ActorRef[MetadataStateActor.MetadataCheckRunnerCommand]]

    createCheckRunnerActor()

    /**
      * Sends a command to the actor under test.
      *
      * @param command the command to be sent
      * @return this test helper
      */
    def sendCommand(command: MetadataStateActor.MetadataCheckRunnerCommand): RunnerTestHelper = {
      checkRunnerActor ! command
      this
    }

    /**
      * Expects that the given command was sent to the retriever actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectRetrieverCommand(command: MetadataStateActor.MetadataRetrieveCommand): RunnerTestHelper = {
      probeRetriever.expectMessage(command)
      this
    }

    /**
      * Expects that a check result with the given optional exclusion is sent
      * to the source check actor.
      *
      * @param optExclusionName the optional exclusion name
      * @return this test helper
      */
    def expectCheckResult(optExclusionName: Option[String] = None): RunnerTestHelper = {
      val optExclusion = optExclusionName map (MetaExclusions(_))
      probeSourceChecker.expectMessage(MetadataStateActor.MetadataCheckResult(optExclusion))
      this
    }

    /**
      * Expects that no message has been sent to the source check actor.
      *
      * @return this test helper
      */
    def expectNoCheckResult(): RunnerTestHelper = {
      probeSourceChecker.expectNoMessage(200.millis)
      this
    }

    /**
      * Prepares the mock for the intervals service to expect an invocation and
      * return a specific result.
      *
      * @param time   the reference time to be passed to the service
      * @param result the result to return
      * @return this test helper
      */
    def prepareIntervalsService(time: LocalDateTime, result: IntervalQueryResult): RunnerTestHelper = {
      initIntervalsServiceResult(intervalService, metadataSourceConfig.resumeIntervals, time, result)
      this
    }

    /**
      * Prepares the mock for the finder service to inspect an invocation and
      * return a specific result.
      *
      * @param metadata the expected metadata string passed in
      * @param result   the result to return
      * @return this test helper
      */
    def prepareFinderService(metadata: String, result: Option[MetadataExclusion] = None): RunnerTestHelper = {
      when(finderService.findMetadataExclusion(metaConfig, metadataSourceConfig, CurrentMetadata(metadata)))
        .thenReturn(result)
      this
    }

    /**
      * Tests that the actor under test has stopped itself.
      *
      * @return this test helper
      */
    def checkActorStopped(): RunnerTestHelper = {
      val probe = testKit.createDeadLetterProbe()
      probe.expectTerminated(checkRunnerActor)
      this
    }

    /**
      * Returns the actor to be tested. This reference is obtained from the
      * parameters passed to the retriever actor factory. Since this factory is
      * invoked asynchronously during the creation of the test actor, the
      * reference may be available only at a later point in time.
      *
      * @return the actor to be tested
      */
    private def checkRunnerActor: ActorRef[MetadataStateActor.MetadataCheckRunnerCommand] = {
      val actorRef = refCheckRunnerActor.get()
      actorRef should not be null
      actorRef
    }

    /**
      * Creates a factory for the retriever actor that checks the passed in
      * parameters and returns a behavior that can be monitored.
      *
      * @return the stub factory for the retriever actor
      */
    private def createRetrieverFactory(): MetadataStateActor.MetadataRetrieveActorFactory =
      (source: RadioSource,
       streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
       checkRunner: ActorRef[MetadataStateActor.MetadataCheckRunnerCommand]) => {
        source should be(TestRadioSource)
        streamManager should be(probeStreamManager.ref)
        refCheckRunnerActor.set(checkRunner)

        Behaviors.monitor[MetadataStateActor.MetadataRetrieveCommand](probeRetriever.ref, Behaviors.ignore)
      }

    /**
      * Creates the test actor instance.
      *
      * @return the actor to be tested
      */
    private def createCheckRunnerActor(): ActorRef[MetadataStateActor.MetadataCheckRunnerCommand] = {
      val behavior = MetadataStateActor.checkRunnerBehavior(TestRadioSource,
        "checker" + counter.incrementAndGet(),
        metaConfig,
        MetaExclusions(currentExclusionStr),
        probeStreamManager.ref,
        intervalService,
        finderService,
        probeSourceChecker.ref,
        createRetrieverFactory())
      testKit.spawn(behavior)
    }
  }

  "Source check actor" should "schedule the initial check" in {
    val expDelay = MetaExclusions(MetaBadMusic).checkInterval
    val helper = new SourceCheckTestHelper

    val schedule = helper.expectScheduledInvocation()

    schedule.delay should be(expDelay)
  }

  it should "support canceling the current check" in {
    val helper = new SourceCheckTestHelper

    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      .expectCheckRunnerTimeout()
      .checkActorNotStopped {
        helper.sendCommand(MetadataStateActor.MetadataCheckResult(None))
      }
  }

  it should "support canceling the current check if no check is ongoing" in {
    val helper = new SourceCheckTestHelper

    helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      .checkActorNotStopped {
        helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      }
  }

  it should "support canceling the current check and stopping itself if no check is ongoing" in {
    val helper = new SourceCheckTestHelper

    helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = true))
      .checkActorStopped()
  }

  it should "support canceling the current check and stopping itself after cancellation is complete" in {
    val helper = new SourceCheckTestHelper

    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .sendCommand(MetadataStateActor.CancelSourceCheck(terminate = true))
      .checkActorNotStopped {
        helper.sendCommand(MetadataStateActor.CancelSourceCheck(terminate = false))
      }
      .sendCommand(MetadataStateActor.MetadataCheckResult(optExclusion = None))
      .checkActorStopped()
  }

  it should "schedule a timeout for a new check" in {
    val helper = new SourceCheckTestHelper
    helper.expectAndTriggerScheduledInvocation()

    val schedule = helper.expectScheduledInvocation()

    schedule.delay should be(TestRadioConfig.metadataCheckTimeout)
    schedule.invocation.send()
    helper.expectSourceCheckTimeout()
  }

  it should "handle a successful check result" in {
    val helper = new SourceCheckTestHelper

    helper.expectAndTriggerScheduledInvocation()
      .sendCommand(MetadataStateActor.MetadataCheckResult(None))
      .expectStateCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .checkActorStopped()
  }

  it should "handle a check result requiring another check" in {
    val helper = new SourceCheckTestHelper
    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .prepareIntervalsService(timeForTicks(2), IntervalTypes.After { time => time })
      .expectScheduledInvocation() // Scheduled timeout

    helper.sendCommand(MetadataStateActor.MetadataCheckResult(MetaExclusions.get(MetaNotWanted)))
      .expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaNotWanted, 2)
  }

  /**
    * Helper function for testing whether further checks are scheduled with
    * corrects delays.
    *
    * @param queryResult the result of the interval service
    * @param expDelay    the expected delay
    */
  private def checkDelayOfNextSchedule(queryResult: IntervalQueryResult, expDelay: FiniteDuration): Unit = {
    val helper = new SourceCheckTestHelper
    helper.expectAndTriggerScheduledInvocation()
      .expectCheckRunnerCreation(MetaBadMusic)
      .prepareIntervalsService(timeForTicks(2), queryResult)
      .expectScheduledInvocation() // Scheduled timeout

    val invocation = helper.sendCommand(MetadataStateActor.MetadataCheckResult(MetaExclusions.get(MetaNotWanted)))
      .expectScheduledInvocation()

    val delta = expDelay - invocation.delay
    delta should be < 3.seconds
  }

  it should "schedule a follow-up check based on the current exclusion" in {
    val queryResult = Before(new LazyDate(timeForTicks(1).plusMinutes(5)))
    val expDelay = MetaExclusions(MetaNotWanted).checkInterval

    checkDelayOfNextSchedule(queryResult, expDelay)
  }

  it should "schedule a follow-up check based on the next resume interval" in {
    val queryResult = Before(new LazyDate(timeForTicks(1).plusSeconds(10)))
    val expDelay = 10.seconds

    checkDelayOfNextSchedule(queryResult, expDelay)
  }

  it should "handle a large temporal delta gracefully when scheduling a follow-up check" in {
    val queryResult = Before(new LazyDate(timeForTicks(1).plusYears(5000)))
    val expDelay = MetaExclusions(MetaNotWanted).checkInterval

    checkDelayOfNextSchedule(queryResult, expDelay)
  }

  /**
    * A test helper class for the source check actor.
    */
  private class SourceCheckTestHelper {
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

    /** Test probe for the stream manager actor. */
    private val probeStreamManager = testKit.createTestProbe[RadioStreamManagerActor.RadioStreamManagerCommand]()

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
    def sendCommand(command: MetadataStateActor.SourceCheckCommand): SourceCheckTestHelper = {
      sourceCheckActor ! command
      this
    }

    /**
      * Prepares the mock for the intervals service to expect an invocation and
      * return a specific result.
      *
      * @param time   the reference time to be passed to the service
      * @param result the result to return
      * @return this test helper
      */
    def prepareIntervalsService(time: LocalDateTime, result: IntervalQueryResult): SourceCheckTestHelper = {
      initIntervalsServiceResult(intervalService, sourceConfig.resumeIntervals, time, result)
      this
    }

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
    def expectAndTriggerScheduledInvocation(): SourceCheckTestHelper = {
      val invocationCommand = expectScheduledInvocation()
      invocationCommand.invocation.send()
      this
    }

    /**
      * Expects that a timeout command has been sent to the check runner.
      *
      * @return this test helper
      */
    def expectCheckRunnerTimeout(): SourceCheckTestHelper = {
      probeCheckRunner.expectMessage(MetadataStateActor.MetadataCheckRunnerTimeout)
      this
    }

    /**
      * Expects the creation of another check runner instance and stores it
      * internally, so that it can be accessed.
      *
      * @param expExclusion the key for the expected current exclusion
      * @param checkIndex   the numeric index expected in the name prefix
      * @return this test helper
      */
    def expectCheckRunnerCreation(expExclusion: String, checkIndex: Int = 1): SourceCheckTestHelper = {
      refProbeChecker set queueCheckerCreation.poll(3, TimeUnit.SECONDS)
      refCurrentExclusion.get() should be(MetaExclusions(expExclusion))
      refNamePrefix.get() should be("testNamePrefix_run" + checkIndex)
      this
    }

    /**
      * Tests that the actor under test has terminated.
      *
      * @return this test helper
      */
    def checkActorStopped(): SourceCheckTestHelper = {
      val probe = testKit.createDeadLetterProbe()
      probe.expectTerminated(sourceCheckActor)
      this
    }

    /**
      * Tests that the actor under test has not terminated. This can be checked
      * indirectly only. The function runs the provided block which should
      * somehow interact with the test actor. It then checks that no dead
      * letter message was received.
      *
      * @param block the block to trigger the test actor
      * @return this test helper
      */
    def checkActorNotStopped(block: => Unit): SourceCheckTestHelper = {
      val probe = testKit.createDeadLetterProbe()
      block
      probe.expectNoMessage(200.millis)
      this
    }

    /**
      * Expects that the given command was sent to the parent state actor.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectStateCommand(command: MetadataStateActor.MetadataExclusionStateCommand): SourceCheckTestHelper = {
      probeStateActor.expectMessage(command)
      this
    }

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
    private def probeCheckRunner: TestProbe[MetadataStateActor.MetadataCheckRunnerCommand] = {
      val probe = refProbeChecker.get()
      probe should not be null
      probe
    }

    /**
      * Creates the mock for the intervals service and prepares it to answer
      * the first invocation.
      *
      * @return the mock intervals service
      */
    private def createIntervalsService(): EvaluateIntervalsService =
      initIntervalsServiceResult(mock[EvaluateIntervalsService], sourceConfig.resumeIntervals, timeForTicks(1),
        Inside(new LazyDate(timeForTicks(1).plusMinutes(2))))

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
       streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
       intervalServiceParam: EvaluateIntervalsService,
       finderServiceParam: MetadataExclusionFinderService,
       sourceChecker: ActorRef[MetadataStateActor.SourceCheckCommand],
       _: MetadataStateActor.MetadataRetrieveActorFactory) => {
        source should be(TestRadioSource)
        metadataConfig should be(metaConfig)
        streamManager should be(probeStreamManager.ref)
        intervalServiceParam should be(intervalService)
        finderServiceParam should be(finderService)
        sourceChecker should be(sourceCheckActor)

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
    private def createSourceCheckerActor(): ActorRef[MetadataStateActor.SourceCheckCommand] = {
      val behavior = MetadataStateActor.sourceCheckBehavior(TestRadioSource,
        "testNamePrefix",
        TestRadioConfig,
        metaConfig,
        MetaExclusions(MetaBadMusic),
        probeStateActor.ref,
        probeScheduler.ref,
        clock,
        probeStreamManager.ref,
        intervalService,
        finderService,
        createCheckRunnerFactory())
      testKit.spawn(behavior)
    }
  }

  /**
    * Checks whether the metadata state actor detects the given metadata
    * exclusion.
    *
    * @param exclusion the exclusion to check
    */
  private def checkMetadataExclusion(exclusion: String): Unit = {
    val helper = new MetadataStateTestHelper

    helper.sendMetadataEvent(exclusion)
      .expectDisabledSource()

    val creation = helper.nextSourceCheckCreation()
    creation.currentExclusion should be(MetaExclusions(exclusion))
    creation.source should be(TestRadioSource)
    creation.namePrefix should be(s"${MetadataStateActor.SourceCheckActorNamePrefix}1")
  }

  "Metadata exclusion state actor" should "detect a global metadata exclusion" in {
    checkMetadataExclusion(MetaBadMusic)
  }

  it should "detect a metadata exclusion for the current source" in {
    checkMetadataExclusion(MetaNotWanted)
  }

  /**
    * Tests whether a radio event unrelated to metadata updates is ignored.
    *
    * @param event the event
    */
  private def checkIrrelevantEvent(event: RadioEvent): Unit = {
    val helper = new MetadataStateTestHelper

    helper.sendEvent(event)
      .expectNoSourceCheckCreation()
      .sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()
  }

  it should "ignore metadata events without current metadata" in {
    checkIrrelevantEvent(RadioMetadataEvent(TestRadioSource, MetadataNotSupported))
  }

  it should "ignore events of other types" in {
    checkIrrelevantEvent(RadioSourceChangedEvent(TestRadioSource))
  }

  it should "ignore metadata not matched by an exclusion" in {
    val helper = new MetadataStateTestHelper

    helper.sendMetadataEvent("no problem")
      .expectNoSourceCheckCreation()
      .sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()
  }

  it should "create different source check actors for different sources" in {
    val source2 = radioSource(2)
    val event2 = RadioMetadataEvent(source2, CurrentMetadata(MetaBadMusic))
    val helper = new MetadataStateTestHelper
    helper.prepareFinderService(MetaBadMusic, source2)
    val creation1 = helper.sendMetadataEvent(MetaNotWanted)
      .nextSourceCheckCreation()

    val creation2 = helper.sendEvent(event2)
      .nextSourceCheckCreation()

    creation2.metadataConfig should be(creation1.metadataConfig)
    creation2.namePrefix should be(s"${MetadataStateActor.SourceCheckActorNamePrefix}2")
  }

  it should "not create multiple check actors per source" in {
    val helper = new MetadataStateTestHelper
    helper.sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()

    helper.sendMetadataEvent(MetaNotWanted)
      .expectNoSourceCheckCreation()
  }

  it should "handle a successful source check" in {
    val helper = new MetadataStateTestHelper
    helper.sendMetadataEvent(MetaNotWanted)
      .expectDisabledSource()
      .nextSourceCheckCreation()

    val creation = helper.sendCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .expectEnabledSource()
      .sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()

    creation.source should be(TestRadioSource)
  }

  it should "ignore a message about a successful source check if the source is not disabled" in {
    val helper = new MetadataStateTestHelper

    helper.sendCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .expectNoSourceEnabledChange()
  }

  it should "forward a source check timeout message" in {
    val helper = new MetadataStateTestHelper
    val creation = helper.sendMetadataEvent(MetaBadMusic)
      .nextSourceCheckCreation()

    helper.sendCommand(MetadataStateActor.SourceCheckTimeout(TestRadioSource, creation.probe.ref))

    creation.probe.expectMessage(MetadataStateActor.CancelSourceCheck(terminate = false))
  }

  it should "ignore a source check timeout message if the source is not disabled" in {
    val helper = new MetadataStateTestHelper
    val creation = helper.sendMetadataEvent(MetaNotWanted)
      .nextSourceCheckCreation()

    helper.sendCommand(MetadataStateActor.SourceCheckSucceeded(TestRadioSource))
      .sendCommand(MetadataStateActor.SourceCheckTimeout(TestRadioSource, creation.probe.ref))

    creation.probe.expectNoMessage(200.millis)
  }

  it should "enable all sources if the metadata config changes" in {
    val SourceCount = 4
    val sources = (1 to SourceCount) map radioSource
    val nextConfig = initMetaConfigMock(mock[MetadataConfig])
    val helper = new MetadataStateTestHelper
    val creations = sources map { source =>
      val event = RadioMetadataEvent(source, CurrentMetadata(MetaBadMusic))
      helper.prepareFinderService(MetaBadMusic, source)
        .sendEvent(event)
        .expectDisabledSource(source)
        .nextSourceCheckCreation()
    }

    helper.sendCommand(MetadataStateActor.InitMetadataConfig(nextConfig))

    creations foreach { creation =>
      creation.probe.expectMessage(MetadataStateActor.CancelSourceCheck(terminate = true))
    }
    val enabledSources = creations map { _ => helper.nextEnabledSource().source }
    enabledSources should contain theSameElementsAs sources

    val nextSource = radioSource(1)
    helper.prepareFinderService(MetaBadMusic, nextSource, nextConfig)
    val nextCreation = helper.sendEvent(RadioMetadataEvent(nextSource, CurrentMetadata(MetaBadMusic)))
      .nextSourceCheckCreation()
    nextCreation.metadataConfig should be(nextConfig)
    nextCreation.namePrefix should be(s"${MetadataStateActor.SourceCheckActorNamePrefix}${SourceCount + 1}")
  }

  /**
    * A helper class for testing the metadata state actor.
    */
  private class MetadataStateTestHelper {
    /** Test probe for the source enabled state actor. */
    private val probeEnabledStateActor = testKit.createTestProbe[RadioControlProtocol.SourceEnabledStateCommand]()

    /** Test probe for the scheduler actor. */
    private val probeSchedulerActor = testKit.createTestProbe[ScheduledInvocationActor.ScheduledInvocationCommand]()

    /** Test probe for the event manager actor. */
    private val probeEventActor = testKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the stream manager actor. */
    private val probeStreamManagerActor = testKit.createTestProbe[RadioStreamManagerActor.RadioStreamManagerCommand]()

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
    def sendCommand(command: MetadataStateActor.MetadataExclusionStateCommand): MetadataStateTestHelper = {
      stateActor ! command
      this
    }

    /**
      * Sends the given event to the registered listener.
      *
      * @param event the radio event to send
      * @return this test helper
      */
    def sendEvent(event: RadioEvent): MetadataStateTestHelper = {
      eventListener ! event
      this
    }

    /**
      * Sends a metadata event with the given text to the registered listener.
      * Also prepares the finder service mock to return the corresponding
      * result.
      *
      * @param data the metadata to send
      * @return this test helper
      */
    def sendMetadataEvent(data: String): MetadataStateTestHelper = {
      prepareFinderService(data)
      sendEvent(RadioMetadataEvent(TestRadioSource, CurrentMetadata(data)))
    }

    /**
      * Prepares the mock for the finder service to expect an invocation and
      * return the corresponding exclusion.
      *
      * @param metadata the metadata passed to the service
      * @param source   the affected radio source
      * @param config   the expected metadata config
      * @return this test helper
      */
    def prepareFinderService(metadata: String,
                             source: RadioSource = TestRadioSource,
                             config: MetadataConfig = metaConfig): MetadataStateTestHelper = {
      when(finderService.findMetadataExclusion(config, config.metadataSourceConfig(source),
        CurrentMetadata(metadata))).thenReturn(MetaExclusions.get(metadata))
      this
    }

    /**
      * Expects the creation of a source check actor and returns its reference.
      *
      * @return the next source check actor created by the test actor
      */
    def nextSourceCheckCreation(): SourceCheckCreation = {
      val checkActor = queueSourceCheckCreations.poll(3, TimeUnit.SECONDS)
      checkActor should not be null
      checkActor
    }

    /**
      * Expects that no source check actor is created.
      *
      * @return this test helper
      */
    def expectNoSourceCheckCreation(): MetadataStateTestHelper = {
      queueSourceCheckCreations.poll(200, TimeUnit.MILLISECONDS) should be(null)
      this
    }

    /**
      * Expects that the given radio source has been disabled.
      *
      * @param source the source in question
      * @return this test helper
      */
    def expectDisabledSource(source: RadioSource = TestRadioSource): MetadataStateTestHelper = {
      probeEnabledStateActor.expectMessage(RadioControlProtocol.DisableSource(source))
      this
    }

    /**
      * Expects that the given radio source has been enabled.
      *
      * @param source the source in question
      * @return this test helper
      */
    def expectEnabledSource(source: RadioSource = TestRadioSource): MetadataStateTestHelper = {
      nextEnabledSource().source should be(source)
      this
    }

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
    def expectNoSourceEnabledChange(): MetadataStateTestHelper = {
      probeEnabledStateActor.expectNoMessage(200.millis)
      this
    }

    /**
      * Returns the event listener the actor under test should have registered
      * at the event manager actor. It is obtained on first access.
      *
      * @return the event listener
      */
    private def eventListener: ActorRef[RadioEvent] = {
      if (refEventListener.get() == null) {
        val reg = probeEventActor.expectMessageType[EventManagerActor.RegisterListener[RadioEvent]]
        refEventListener set reg.listener
      }
      refEventListener.get()
    }

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
       streamManager: ActorRef[RadioStreamManagerActor.RadioStreamManagerCommand],
       intervalServiceParam: EvaluateIntervalsService,
       finderServiceParam: MetadataExclusionFinderService,
       _: MetadataStateActor.MetadataCheckRunnerFactory) => {
        radioConfig should be(TestRadioConfig)
        clockParam should be(clock)
        streamManager should be(probeStreamManagerActor.ref)
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
    private def createStateActor(): ActorRef[MetadataStateActor.MetadataExclusionStateCommand] = {
      val behavior = MetadataStateActor.metadataStateBehavior(TestRadioConfig,
        probeEnabledStateActor.ref,
        probeSchedulerActor.ref,
        probeEventActor.ref,
        probeStreamManagerActor.ref,
        intervalsService,
        finderService,
        clock = clock,
        sourceCheckFactory = createSourceCheckerFactory())
      val actor = testKit.spawn(behavior)

      actor ! MetadataStateActor.InitMetadataConfig(metaConfig)
      actor
    }
  }
}
