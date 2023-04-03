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

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.stream.scaladsl.{Broadcast, Flow, Framing, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ClosedShape, KillSwitch, KillSwitches}
import akka.util.ByteString
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.MatchContext.MatchContext
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.ResumeMode.ResumeMode
import de.oliver_heger.linedj.player.engine.radio.config.MetadataConfig.{MatchContext, RadioSourceMetadataConfig, ResumeMode}
import de.oliver_heger.linedj.player.engine.radio.stream.{RadioStreamBuilder, RadioStreamTestHelper}
import de.oliver_heger.linedj.player.engine.radio.{CurrentMetadata, RadioSource}
import org.mockito.ArgumentMatchers.{any, eq => eqArg}
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.{PipedInputStream, PipedOutputStream}
import java.nio.charset.StandardCharsets
import java.time._
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Using

object MetadataCheckActorSpec {
  /** Constant for a reference instant. */
  private val RefInstant = Instant.parse("2023-03-31T20:37:16Z")

  /** The reference local time corresponding to the reference instant. */
  private val RefTime = LocalDateTime.ofInstant(RefInstant, ZoneOffset.UTC)

  /** A prefix that marks a string as metadata. */
  private val MetadataPrefix = "meta:"

  /**
    * A delimiter to separate between different data blocks in simulated radio
    * streams.
    */
  private val RadioStreamDelimiter = "\n"

  /** A radio source used by tests. */
  private val TestRadioSource = RadioSource("sourceWithExcludedMetadata")

  /** A test audio player configuration object. */
  private val TestPlayerConfig = PlayerConfig(mediaManagerActor = null, actorCreator = null)

  /** A regular expression pattern to extract artist and song title. */
  private val RegSongData = Pattern.compile(s"(?<${MetadataConfig.ArtistGroup}>[^/]+)/\\s*" +
    s"(?<${MetadataConfig.SongTitleGroup}>.+)")

  /**
    * Checks whether the given chunk of data represents metadata.
    *
    * @param chunk the chunk
    * @return a flag whether this chunk represents metadata
    */
  private def isMetadata(chunk: ByteString) = chunk.startsWith(MetadataPrefix)

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
    * Convenience function to create a metadata exclusion with default values.
    *
    * @param pattern       the pattern
    * @param matchContext  the match context
    * @param resumeMode    the resume mode
    * @param checkInterval the check interval
    * @param name          the optional name
    * @return the exclusion instance
    */
  private def createExclusion(pattern: Pattern = Pattern.compile(".*match.*"),
                              matchContext: MatchContext = MatchContext.Raw,
                              resumeMode: ResumeMode = ResumeMode.MetadataChange,
                              checkInterval: FiniteDuration = 10.seconds,
                              name: Option[String] = None): MetadataConfig.MetadataExclusion =
    MetadataConfig.MetadataExclusion(pattern, matchContext, resumeMode, checkInterval, name)
}

/**
  * Test class for [[MetadataCheckActor]].
  */
class MetadataCheckActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar {

  import MetadataCheckActorSpec._

  "Metadata retriever actor" should "send the latest metadata" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .writeMetadata(1)
        .sendRetrieverCommand(MetadataCheckActor.GetMetadata)
        .checkRunnerCommand(MetadataCheckActor.MetadataRetrieved(CurrentMetadata(metadata(1)), timeForTicks(1)))
    }
  }

  it should "ignore audio data in the radio stream" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .writeData("foo_foo_foo")
        .sendRetrieverCommand(MetadataCheckActor.GetMetadata)
        .expectNoCheckRunnerCommand()
        .writeMetadata(1)
        .checkRunnerCommand(MetadataCheckActor.MetadataRetrieved(CurrentMetadata(metadata(1)), timeForTicks(1)))
    }
  }

  it should "reset the pending request flag after sending metadata" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .writeMetadata(1)
        .sendRetrieverCommand(MetadataCheckActor.GetMetadata)
        .expectCheckRunnerCommand()

      helper.writeMetadata(2)
        .expectNoCheckRunnerCommand()
    }
  }

  it should "only send metadata if it has changed" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .sendRetrieverCommand(MetadataCheckActor.GetMetadata)
        .writeMetadata(1)
        .checkRunnerCommand(MetadataCheckActor.MetadataRetrieved(CurrentMetadata(metadata(1)), timeForTicks(1)))
        .writeMetadata(1)
        .sendRetrieverCommand(MetadataCheckActor.GetMetadata)
        .writeMetadata(2)
        .checkRunnerCommand(MetadataCheckActor.MetadataRetrieved(CurrentMetadata(metadata(2)), timeForTicks(3)))
    }
  }

  it should "stop the radio stream when receiving a CancelStream command" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .writeMetadata(1)
        .sendRetrieverCommand(MetadataCheckActor.GetMetadata)
        .expectCheckRunnerCommand()

      helper.sendRetrieverCommand(MetadataCheckActor.CancelStream)
        .verifyStreamCanceled()
    }
  }

  it should "handle a CancelStream command before receiving the stream builder result" in {
    retrieverTest { helper =>
      helper.sendRetrieverCommand(MetadataCheckActor.CancelStream)
        .initSuccessStreamBuilderResult()
        .verifyStreamCanceled()
    }
  }

  it should "handle a failure result from the stream builder" in {
    retrieverTest { helper =>
      helper.initFailedStreamBuilderResult()
        .checkRunnerCommand(MetadataCheckActor.RadioStreamStopped)
    }
  }

  it should "notify the check runner about a completed stream" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .writeData("some audio data")
        .cancelRadioStream()
        .checkRunnerCommand(MetadataCheckActor.RadioStreamStopped)
    }
  }

  it should "notify the check runner about a failed stream" in {
    retrieverTest { helper =>
      helper.initSuccessStreamBuilderResult()
        .writeMetadata(42)
        .failRadioStream()
        .checkRunnerCommand(MetadataCheckActor.RadioStreamStopped)
    }
  }

  "findMetadataExclusion" should "return None if there are no exclusions" in {
    val metadata = CurrentMetadata("some metadata")

    MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, MetadataConfig.EmptySourceConfig,
      metadata) shouldBe empty
  }

  it should "find an exclusion in the raw metadata" in {
    val metadata = CurrentMetadata("This is a match, yeah!")
    val exclusion = createExclusion()
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "return None if there is no match" in {
    val metadata = CurrentMetadata("Some other metadata")
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(createExclusion()))

    MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find an exclusion in the stream title" in {
    val metadata = CurrentMetadata("other;StreamTitle='A match in the title';foo='bar';")
    val exclusion = createExclusion(matchContext = MatchContext.Title)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "evaluate the stream title context" in {
    val metadata = CurrentMetadata("other='Would be a match';StreamTitle='But not here';")
    val exclusion = createExclusion(matchContext = MatchContext.Title)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find an exclusion in the artist" in {
    val metadata = CurrentMetadata("StreamTitle='Artist match /song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "evaluate the artist context" in {
    val metadata = CurrentMetadata("StreamTitle='unknown/song title match';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find an exclusion in the song title" in {
    val metadata = CurrentMetadata("StreamTitle='Artist name /matching song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "evaluate the song title context" in {
    val metadata = CurrentMetadata("StreamTitle='artist match/ unknown song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(optSongPattern = Some(RegSongData), exclusions = Seq(exclusion))

    MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata) shouldBe empty
  }

  it should "find a match in the artist if no song title pattern is defined for the source" in {
    val metadata = CurrentMetadata("StreamTitle='unknown/song title match';")
    val exclusion = createExclusion(matchContext = MatchContext.Artist)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "find a match in the song title if no song title pattern is defined for the source" in {
    val metadata = CurrentMetadata("StreamTitle='artist match/unknown song title';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val sourceConfig = RadioSourceMetadataConfig(exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(MetadataConfig.Empty, sourceConfig, metadata)

    result should be(Some(exclusion))
  }

  it should "find a match in global exclusions" in {
    val metadata = CurrentMetadata("StreamTitle='artist/match song';")
    val exclusion = createExclusion(matchContext = MatchContext.Song)
    val metaConfig = MetadataConfig(exclusions = Seq(exclusion))

    val result = MetadataCheckActor.findMetadataExclusion(metaConfig, MetadataConfig.EmptySourceConfig, metadata)

    result should be(Some(exclusion))
  }

  /**
    * Helper function to run a test using [[RetrieverTestHelper]] that makes
    * sure that the helper is closed afterwards.
    *
    * @param block the block containing the actual test
    */
  private def retrieverTest(block: RetrieverTestHelper => Unit): Unit = {
    Using(new RetrieverTestHelper)(block).get
  }

  /**
    * Test helper class for testing the metadata retriever actor.
    */
  private class RetrieverTestHelper extends AutoCloseable {
    /** Test probe for the check runner actor. */
    private val probeRunner = testKit.createTestProbe[MetadataCheckActor.MetadataCheckRunnerCommand]()

    /** A stream to be used for the data source of the radio stream. */
    private val radioStream = new PipedOutputStream

    /** A kill switch to terminate the radio stream. */
    private val radioStreamKillSwitch = KillSwitches.shared("radioStream")

    /** A promise for defining the result of the stream builder. */
    private val promiseRadioStream = Promise[RadioStreamBuilder.BuilderResult[Future[Done], Future[Done]]]()

    /** A mock KillSwitch to be used in the result of the stream builder. */
    private val mockKillSwitch = mock[KillSwitch]

    /** Mock for the stream builder passed to the test actor. */
    private val streamBuilder = createStreamBuilderMock()

    /** The retriever actor to be tested. */
    private val retrieverActor = createRetrieverActor()

    /**
      * @inheritdoc This implementation closes the simulated radio stream.
      */
    override def close(): Unit = {
      radioStream.close()
    }

    /**
      * Writes a block of test metadata into the radio stream.
      *
      * @param index the index of the metadata
      * @return this test helper
      */
    def writeMetadata(index: Int): RetrieverTestHelper =
      writeData(MetadataPrefix + metadata(index))

    /**
      * Writes the given string of data into the radio stream.
      *
      * @param data the data to be written
      * @return this test helper
      */
    def writeData(data: String): RetrieverTestHelper = {
      radioStream.write((data + RadioStreamDelimiter).getBytes(StandardCharsets.UTF_8))
      radioStream.flush()
      this
    }

    /**
      * Initializes a successful result for the stream builder. This result
      * injects the test radio stream into the actor to be tested.
      *
      * @return this test helper
      */
    def initSuccessStreamBuilderResult(): RetrieverTestHelper = {
      val captorAudioSink = ArgumentCaptor.forClass(classOf[Sink[ByteString, Future[Done]]])
      val captorMetaSink = ArgumentCaptor.forClass(classOf[Sink[ByteString, Future[Done]]])
      verify(streamBuilder, Mockito.timeout(3000)).buildRadioStream(eqArg(TestPlayerConfig),
        eqArg(TestRadioSource.uri),
        captorAudioSink.capture(),
        captorMetaSink.capture())(any(), any())
      val result = RadioStreamBuilder.BuilderResult("someResolvedUri",
        createGraphForRadioStream(captorAudioSink.getValue, captorMetaSink.getValue),
        mockKillSwitch,
        metadataSupported = true)
      promiseRadioStream.success(result)
      this
    }

    /**
      * Initializes a failure result for the stream builder.
      *
      * @return this test helper
      */
    def initFailedStreamBuilderResult(): RetrieverTestHelper = {
      promiseRadioStream.failure(new IllegalStateException("Test exception"))
      this
    }

    /**
      * Sends the given command to the retriever actor under test.
      *
      * @param command the command
      * @return this test helper
      */
    def sendRetrieverCommand(command: MetadataCheckActor.MetadataRetrieveCommand): RetrieverTestHelper = {
      retrieverActor ! command
      this
    }

    /**
      * Expects that a command has been sent to the check runner actor and
      * returns it.
      *
      * @return the command sent to the check runner actor
      */
    def expectCheckRunnerCommand(): MetadataCheckActor.MetadataCheckRunnerCommand =
      probeRunner.expectMessageType[MetadataCheckActor.MetadataCheckRunnerCommand]

    /**
      * Expects that the given command was sent to the check runner actor.
      *
      * @param expectedCommand the expected command
      * @return this test helper
      */
    def checkRunnerCommand(expectedCommand: MetadataCheckActor.MetadataCheckRunnerCommand): RetrieverTestHelper = {
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
      * Verifies that the kill switch has been invoked to stop the radio
      * stream.
      *
      * @return this test helper
      */
    def verifyStreamCanceled(): RetrieverTestHelper = {
      verify(mockKillSwitch, Mockito.timeout(3000)).shutdown()
      this
    }

    /**
      * Stops the radio stream.
      *
      * @return this test helper
      */
    def cancelRadioStream(): RetrieverTestHelper = {
      radioStreamKillSwitch.shutdown()
      this
    }

    /**
      * Causes the radio stream to crash with a failure.
      *
      * @return this test helper
      */
    def failRadioStream(): RetrieverTestHelper = {
      radioStreamKillSwitch.abort(new IllegalStateException("Radio stream failure!"))
      this
    }

    /**
      * Creates the metadata retriever actor to be tested.
      *
      * @return the retriever actor to be tested
      */
    private def createRetrieverActor(): ActorRef[MetadataCheckActor.MetadataRetrieveCommand] =
      testKit.spawn(MetadataCheckActor.retrieveMetadataBehavior(TestRadioSource,
        TestPlayerConfig,
        tickSecondsClock(),
        streamBuilder,
        probeRunner.ref))

    /**
      * Creates a [[RunnableGraph]] that simulates a radio stream. The stream
      * source is a piped output stream. Via this stream, data can be written
      * that is interpreted as metadata or audio data.
      *
      * @param sinkAudio the sink for audio data
      * @param sinkMeta  the sink for metadata
      * @return the graph simulating the radio stream
      */
    private def createGraphForRadioStream(sinkAudio: Sink[ByteString, Future[Done]],
                                          sinkMeta: Sink[ByteString, Future[Done]]):
    RunnableGraph[(Future[Done], Future[Done])] = {
      val inputStream = new PipedInputStream(radioStream)
      val source = RadioStreamTestHelper.createSourceFromStream(inputStream)
      val framing = Framing.delimiter(ByteString(RadioStreamDelimiter), 16384)
      val filterMeta = Flow[ByteString].filter(isMetadata)
      val mapMeta = Flow[ByteString].map(_.drop(MetadataPrefix.length))
      val filterAudio = Flow[ByteString].filterNot(isMetadata)
      RunnableGraph.fromGraph(GraphDSL.createGraph(sinkAudio, sinkMeta)((_, _)) {
        implicit builder =>
          (sink1, sink2) =>
            import GraphDSL.Implicits._

            val ks = builder.add(radioStreamKillSwitch.flow[ByteString])
            val broadcast = builder.add(Broadcast[ByteString](2))

            source ~> framing ~> ks ~> broadcast ~> filterAudio ~> sink1
            broadcast ~> filterMeta ~> mapMeta ~> sink2
            ClosedShape
      })
    }

    /**
      * Creates a mock [[RadioStreamBuilder]] that is prepared to expect an
      * invocation and return the future of the promise that can be used to set
      * the builder result later.
      *
      * @return the mock stream builder
      */
    private def createStreamBuilderMock(): RadioStreamBuilder = {
      val builder = mock[RadioStreamBuilder]
      when(builder.buildRadioStream(eqArg(TestPlayerConfig),
        eqArg(TestRadioSource.uri),
        any[Sink[ByteString, Future[Done]]](),
        any[Sink[ByteString, Future[Done]]]())(any(), any()))
        .thenReturn(promiseRadioStream.future)
      builder
    }
  }
}
