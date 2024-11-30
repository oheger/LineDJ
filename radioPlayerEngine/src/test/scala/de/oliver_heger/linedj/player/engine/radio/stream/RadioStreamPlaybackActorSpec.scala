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

package de.oliver_heger.linedj.player.engine.radio.stream

import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.actors.EventManagerActor
import de.oliver_heger.linedj.player.engine.radio.*
import de.oliver_heger.linedj.player.engine.stream.{AudioStreamTestHelper, LineWriterStage}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.FishingOutcome
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.{ByteString, Timeout}
import org.mockito.ArgumentMatchers.{any, eq as eqArg}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Inspectors.forEvery
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.{AudioInputStream, SourceDataLine}
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Random, Success, Try}

object RadioStreamPlaybackActorSpec:
  /** The limit for calling the audio stream factory. */
  private val StreamFactoryLimit = 128

  /** A timeout value for the test configuration. */
  private val TestTimeout = Timeout(11.seconds)

  /** A random object for generating content for radio sources. */
  private val random = Random(20241123214707L)

  /**
    * Generates random data for an audio source of the given size.
    *
    * @param size the size of data to generate
    * @return a byte string with the generated data
    */
  private def createSourceData(size: Int): ByteString =
    ByteString(random.nextBytes(size))

  /**
    * Returns the encoded data from the given input based on the test encoding
    * function.
    *
    * @param data the data to be encoded
    * @return the encoded data
    */
  private def encode(data: ByteString): ByteString =
    ByteString(AudioStreamTestHelper.encodeBytes(data.toArray))

  /**
    * Creates an [[AudioInputStream]] for a given input stream. This function
    * is used as stream creator for the test audio stream factory. It returns a
    * [[AudioStreamTestHelper.DummyEncoderStream]] object.
    *
    * @param in the underlying input stream
    * @return the audio stream
    */
  private def createAudioStream(in: InputStream): AudioInputStream =
    new AudioStreamTestHelper.DummyEncoderStream(in, new LinkedBlockingQueue)
end RadioStreamPlaybackActorSpec

/**
  * Test class for [[RadioStreamPlaybackActor]].
  */
class RadioStreamPlaybackActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:
  def this() = this(ActorSystem("RadioStreamPlaybackActorSpec"))

  /** A test kit for testing typed actors. */
  private val actorTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    actorTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import RadioStreamPlaybackActorSpec.*

  /**
    * Creates a mock [[RadioStreamHandle]] that returns sources with the given
    * data when it is attached.
    *
    * @param audioData the data for the audio source
    * @param metadata  the single metadata strings for the metadata source
    * @return the mock stream handle
    */
  def createMockHandle(audioData: ByteString, metadata: List[String]): RadioStreamHandle =
    val handle = mock[RadioStreamHandle]
    val audioSource = Source(audioData.grouped(64).toList)
    val metaSource = Source(metadata.map(metaStr => ByteString(metaStr)))

    given ActorSystem = any()

    when(handle.attachOrCancel(eqArg(TestTimeout)))
      .thenReturn(Future.successful((audioSource, metaSource)))
    handle

  "RadioStreamPlaybackActor" should "play a radio source" in :
    val radioSource = RadioSource("testRadioSource.mp3")
    val audioData = createSourceData(2048)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequestWithData(radioSource, audioData, Nil)

    val messages = helper.fishForEvents {
      case _: RadioSourceErrorEvent =>
        FishingOutcome.Complete
      case _ => FishingOutcome.ContinueAndIgnore
    }
    messages should have size 1
    messages.head match
      case RadioSourceErrorEvent(source, _) =>
        source should be(radioSource)
      case e => fail("Unexpected radio event: " + e)

    helper.playedRadioSourceUris should contain only radioSource.uri
    helper.lineInput should be(encode(audioData))

  it should "generate an event about a newly started radio source" in :
    val radioSource = RadioSource("startedSource.mp3")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequestWithData(radioSource, createSourceData(1024), Nil)

    val events = helper.fishForEvents {
      case _: RadioSourceChangedEvent => FishingOutcome.Complete
      case e => FishingOutcome.Fail("Unexpected event: " + e)
    }
    events should have size 1
    events.head match
      case RadioSourceChangedEvent(source, _) =>
        source should be(radioSource)
      case e => fail("Unexpected event: " + e)

  it should "generate playback progress events during audio playback" in :
    val radioSource = RadioSource("progressSource.mp3")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequestWithData(radioSource, createSourceData(32768), Nil)

    val progressMessages = helper.fishForEvents {
      case e: RadioSourceErrorEvent =>
        FishingOutcome.Complete
      case e: RadioPlaybackProgressEvent =>
        FishingOutcome.Continue
      case _ => FishingOutcome.ContinueAndIgnore
    }.init

    val expectedParameters = List(
      (8161, 10929706.nanos),
      (16324, 21859412.nanos),
      (24491, 32789118.nanos),
      (32642, 43718824.nanos)
    )
    progressMessages should have size expectedParameters.size
    forEvery(progressMessages.zip(expectedParameters)) { (event, param) =>
      event match
        case e: RadioPlaybackProgressEvent =>
          e.source should be(radioSource)
          e.bytesProcessed should be(param._1)
          e.playbackTime should be(param._2)
        case e => fail("Unexpected event: " + e)
    }

  it should "generate metadata events during audio playback" in :
    val radioSource = RadioSource("metadataSource.mp3")
    val metadata = List("metadata1", "more metadata", "further metadata")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequestWithData(radioSource, createSourceData(8192), metadata)

    val metadataMessages = helper.fishForEvents {
      case e: RadioSourceErrorEvent =>
        FishingOutcome.Complete
      case e: RadioMetadataEvent =>
        FishingOutcome.Continue
      case _ => FishingOutcome.ContinueAndIgnore
    }.init

    metadataMessages should have size metadata.size
    forEvery(metadataMessages.zip(metadata)) { (event, meta) =>
      event match
        case e: RadioMetadataEvent =>
          e.source should be(radioSource)
          e.metadata should be(CurrentMetadata(meta))
        case e => fail("Unexpected event: " + e)
    }

  /**
    * A test helper class managing an actor under test and its dependencies.
    */
  private class PlaybackActorTestHelper:
    /** Test probe for the event manager actor. */
    private val probeEventActor: TestProbe[EventManagerActor.EventManagerCommand[RadioEvent]] =
      actorTestKit.createTestProbe[EventManagerActor.EventManagerCommand[RadioEvent]]()

    /** Test probe for the stream handle actor. */
    private val probeHandleActor =
      actorTestKit.createTestProbe[RadioStreamHandleManagerActor.RadioStreamHandleCommand]()

    /** Stores the URIs that are passed to the audio stream factory. */
    private val audioStreamUris = new AtomicReference[List[String]](Nil)

    /** Stores the data that was passed to the line. */
    private val lineData = new AtomicReference[ByteString](ByteString.empty)

    /** The mock for the line returned by the line creator function. */
    private val line = createLine()

    /** The actor instance to be tested. */
    private val playbackActor = createPlaybackActor()

    /**
      * Returns the URIs of the radio sources that have been played by the
      * stream managed by the test actor.
      *
      * @return the list of played radio sources
      */
    def playedRadioSourceUris: List[String] = audioStreamUris.get().reverse

    /**
      * Returns the data that was passed to the audio line.
      *
      * @return the data passed to the line
      */
    def lineInput: ByteString = lineData.get()

    /**
      * Sends a command to the actor under test.
      *
      * @param command the command to send
      * @return this test helper
      */
    def sendCommand(command: RadioStreamPlaybackActor.RadioStreamPlaybackCommand): PlaybackActorTestHelper =
      playbackActor ! command
      this

    /**
      * Expects a request to the handle actor for the given radio source. The
      * request is answered based on the provided parameters.
      *
      * @param expectedSource the expected radio source
      * @param triedHandle    the handle result to return
      * @param optMetadata    the optional last metadata for this source
      * @return this test helper
      */
    def answerHandleRequest(expectedSource: RadioSource,
                            triedHandle: Try[RadioStreamHandle],
                            optMetadata: Option[CurrentMetadata] = None): PlaybackActorTestHelper =
      val request = probeHandleActor.expectMessageType[RadioStreamHandleManagerActor.GetStreamHandle]
      request.params.streamSource should be(expectedSource)
      request.params.streamName should be(RadioStreamPlaybackActor.PlaybackStreamName)
      val response = RadioStreamHandleManagerActor.GetStreamHandleResponse(
        source = expectedSource,
        triedStreamHandle = triedHandle,
        optLastMetadata = optMetadata
      )
      request.replyTo ! response
      this

    /**
      * Expects a request to the handle actor for the given radio source that
      * should yield a handle with the provided data. The function creates a
      * corresponding mock handle and passes it to the requesting party.
      *
      * @param expectedSource the expected radio source
      * @param audioData      the audio data for the mock handle
      * @param metadata       the metadata for the mock handle
      * @param optMetadata    the optional last metadata for this source
      * @return this test helper
      */
    def answerHandleRequestWithData(expectedSource: RadioSource,
                                    audioData: ByteString,
                                    metadata: List[String],
                                    optMetadata: Option[CurrentMetadata] = None): PlaybackActorTestHelper =
      val handle = createMockHandle(audioData, metadata)
      answerHandleRequest(expectedSource, Success(handle), optMetadata)

    /**
      * Helper function for finding specific events published via the event
      * actor. The function expects that publish events are sent to the event
      * manager. It invokes the given ''fisher'' function to decide, which of
      * them should be collected.
      *
      * @param fisher the fisher function
      * @return a sequence with the selected events
      */
    def fishForEvents(fisher: RadioEvent => FishingOutcome): Seq[RadioEvent] =
      probeEventActor.fishForMessage(3.seconds) {
        case EventManagerActor.Publish(event: RadioEvent) =>
          fisher(event)
        case e => FishingOutcome.Fail("Unexpected event: " + e)
      }.map {
        case EventManagerActor.Publish(event: RadioEvent) => event
        case _ => fail("Cannot happen!")
      }

    /**
      * Creates an [[AudioStreamFactory]] to be used by the test actor. The
      * factory records the URI and returns a decoding stream.
      *
      * @return the factory for audio streams
      */
    private def createAudioStreamFactory(): AudioStreamFactory =
      (uri: String) =>
        val urisList = audioStreamUris.get()
        audioStreamUris.set(uri :: urisList)
        Some(AudioStreamFactory.AudioStreamPlaybackData(createAudioStream, StreamFactoryLimit))

    /**
      * Creates a mock for a line that records the data that is written to it.
      *
      * @return the mock for the line
      */
    private def createLine(): SourceDataLine =
      val line = mock[SourceDataLine]
      when(line.write(any(), any(), any())).thenAnswer((invocation: InvocationOnMock) =>
        val data = invocation.getArgument[Array[Byte]](0)
        val offset = invocation.getArgument[Int](1)
        val len = invocation.getArgument[Int](2)
        val str = ByteString.fromArray(data, offset, len)
        lineData.set(lineData.get() ++ str)
        len)
      line

    /**
      * Returns a line creator function to be used by the audio player stage.
      * The function returns the mock line which records the received audio
      * data.
      *
      * @return the line creator function
      */
    private def createLineCreator(): LineWriterStage.LineCreatorFunc =
      header =>
        header.format should be(AudioStreamTestHelper.Format)
        line

    /**
      * Creates the actor to be tested.
      *
      * @return the actor under test
      */
    private def createPlaybackActor(): ActorRef[RadioStreamPlaybackActor.RadioStreamPlaybackCommand] =
      val config = RadioStreamPlaybackActor.RadioStreamPlaybackConfig(
        audioStreamFactory = createAudioStreamFactory(),
        handleActor = probeHandleActor.ref,
        eventActor = probeEventActor.ref,
        lineCreatorFunc = createLineCreator(),
        timeout = TestTimeout,
        progressEventThreshold = 10.millis
      )
      actorTestKit.spawn(RadioStreamPlaybackActor.behavior(config))
