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
import org.mockito.Mockito.{timeout, times, verify, when}
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
import scala.annotation.tailrec
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.reflect.ClassTag
import scala.util.{Failure, Random, Success, Try}

object RadioStreamPlaybackActorSpec:
  /** The limit for calling the audio stream factory. */
  private val StreamFactoryLimit = 128

  /** A timeout value for the test configuration. */
  private val TestTimeout = Timeout(11.seconds)

  /** A random object for generating content for radio sources. */
  private val random = Random(20241123214707L)

  /**
    * A data class containing the data that needs to be stored to answer a
    * request for a radio stream handle.
    *
    * @param triedHandle     the handle to be returned
    * @param optLastMetadata optional last metadata for this stream
    */
  private case class SourceHandleData(triedHandle: Try[RadioStreamHandle],
                                      optLastMetadata: Option[CurrentMetadata])

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

  /**
    * Filters a collection for objects of a given type.
    *
    * @param collection the collection to filter
    * @tparam T the type of the desired objects
    * @return the filtered collection
    */
  private def filterType[T: ClassTag](collection: Seq[Any]): Seq[T] =
    collection.flatMap {
      case e: T => Some(e)
      case _ => None
    }
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
    * @param cyclic    a flag whether endless cyclic sources should be created
    * @return the mock stream handle
    */
  def createMockHandle(audioData: ByteString, metadata: List[String], cyclic: Boolean = false): RadioStreamHandle =
    val handle = mock[RadioStreamHandle]
    val audioStreamData = audioData.grouped(64).toList
    val metaStreamData = metadata.map(metaStr => ByteString(metaStr))
    val (audioSource, metaSource) = if cyclic then
      (Source.cycle(() => audioStreamData.iterator), Source.cycle(() => metaStreamData.iterator))
    else
      (Source(audioStreamData), Source(metaStreamData))

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

  it should "send an error event if a radio source cannot be started" in :
    val errorSource = RadioSource("errorSource.mp3")
    val errorSourceFailure = new IllegalStateException("Test exception: Failed to load radio source.")
    val nextSource = RadioSource("successSource.mp3")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(errorSource))
      .answerHandleRequest(errorSource, Failure(errorSourceFailure))
      .sendCommand(RadioStreamPlaybackActor.PlayRadioSource(nextSource))
      .answerHandleRequestWithData(nextSource, createSourceData(1024), List("metadata"))

    val events = helper.fishForEvents {
      case e: RadioSourceErrorEvent =>
        if e.source == errorSource then FishingOutcome.Continue
        else FishingOutcome.Complete
      case e: RadioMetadataEvent =>
        FishingOutcome.Continue
      case _ =>
        FishingOutcome.ContinueAndIgnore
    }

    val metadataEvents = filterType[RadioMetadataEvent](events)
    metadataEvents should have size 1
    metadataEvents.head.metadata should be(CurrentMetadata("metadata"))

    val errorEvents = filterType[RadioSourceErrorEvent](events).map(_.source)
    errorEvents should contain theSameElementsInOrderAs List(errorSource, nextSource)

  it should "send a metadata event if there is already metadata present when obtaining the handle" in :
    val radioSource = RadioSource("plentyOfMetadata.mp3")
    val metadata = List("metadata1", "more metadata", "further metadata")
    val initialMetadata = "The original metadata"
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequestWithData(
        radioSource,
        createSourceData(1024),
        metadata,
        optMetadata = Some(CurrentMetadata(initialMetadata))
      )

    val metadataEvents = filterType[RadioMetadataEvent](helper.fishForEvents {
      case e: RadioSourceErrorEvent =>
        FishingOutcome.Complete
      case e: RadioMetadataEvent =>
        FishingOutcome.Continue
      case _ => FishingOutcome.ContinueAndIgnore
    })

    metadataEvents.head.metadata should be(CurrentMetadata(initialMetadata))
    val expectedMetadata = metadata.map(CurrentMetadata.apply)
    metadataEvents.tail.map(_.metadata) should contain theSameElementsInOrderAs expectedMetadata

  it should "switch to a new source" in :
    val radioSource1 = RadioSource("interruptedSource.mp3")
    val radioSource2 = RadioSource("nextSource.mp3")
    val audioData2 = createSourceData(8192)
    val metadata2 = List("metadata2.1", "metadata2.2", "metadata2.3")
    val handle1 = createMockHandle(createSourceData(4096), List("metadata1.1", "metadata1.2"), cyclic = true)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource1))
      .answerHandleRequest(
        radioSource1,
        Success(handle1)
      )
      .fishForEvents {
        case e: RadioSourceChangedEvent if e.source == radioSource1 =>
          FishingOutcome.Complete
        case e => FishingOutcome.Fail("Unexpected event: " + e)
      }

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource2))
      .answerHandleRequestWithData(radioSource2, audioData2, metadata2)

    val events = helper.fishForEvents {
      case e: RadioSourceErrorEvent =>
        FishingOutcome.Complete
      case _ => FishingOutcome.Continue
    }
    filterType[RadioSourceErrorEvent](events).map(_.source) should contain only radioSource2
    val expectedMetadata = metadata2.map(CurrentMetadata.apply)
    val metadata2Events = filterType[RadioMetadataEvent](events).filter(_.source == radioSource2).map(_.metadata)
    metadata2Events should contain theSameElementsInOrderAs expectedMetadata
    val expectedAudioData = encode(audioData2)
    helper.lineInput.takeRight(expectedAudioData.size) should be(expectedAudioData)
    verify(handle1).cancelStream()

  it should "handle multiple switch source commands arriving in a short time" in :
    val initialSource = RadioSource("initialSource.mp3")
    val switchSources = (1 to 4).map(idx => RadioSource(s"intermediateSource$idx.mp3"))
    val finalSource = RadioSource("finalSource.mp3")
    val finalSourceAudioData = createSourceData(8192)
    val finalSourceMetadata = CurrentMetadata("final source metadata")
    val finalSourceHandle = createMockHandle(finalSourceAudioData, Nil)
    val helper = new PlaybackActorTestHelper
    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(initialSource))
      .answerHandleRequest(
        initialSource,
        Success(createMockHandle(createSourceData(1024), Nil, cyclic = true))
      )

    val switchSourceData = switchSources.map { source =>
      val handleData = SourceHandleData(
        Success(createMockHandle(createSourceData(1024), List(source.uri), cyclic = true)),
        optLastMetadata = None
      )
      source -> handleData
    }.toList
    val finalSourceData = SourceHandleData(Success(finalSourceHandle), Some(finalSourceMetadata))
    val sourceData = ((finalSource -> finalSourceData) :: switchSourceData).toMap
    switchSources.foreach { source =>
      helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(source))
    }

    @tailrec def waitForFinalSourceRequest(): Unit =
      val requestedSource = helper.answerAnyHandleRequest(sourceData)
      if requestedSource != finalSource then
        waitForFinalSourceRequest()

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(finalSource))
    waitForFinalSourceRequest()

    val events = helper.fishForEvents {
      case e: RadioSourceErrorEvent => FishingOutcome.Complete
      case _ => FishingOutcome.Continue
    }
    helper.playedRadioSourceUris.last should be(finalSource.uri)
    filterType[RadioMetadataEvent](events).last.metadata should be(finalSourceMetadata)

  it should "close a handle that arrives after selecting a new source" in :
    val radioSource1 = RadioSource("firstDelayed.mp3")
    val radioSource2 = RadioSource("fastNext.mp3")
    val handle = createMockHandle(createSourceData(2048), Nil, cyclic = true)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource1))
      .sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource2))
      .answerHandleRequest(radioSource1, Success(handle))

    verify(handle, timeout(3000)).cancelStream()

  it should "suppress playback progress events for the previous source after switching to a new one" in :
    val firstSource = RadioSource("first.mp3")
    val secondSource = RadioSource("second.mp3")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(firstSource))
      .answerHandleRequest(
        firstSource,
        Success(createMockHandle(createSourceData(32768), Nil, cyclic = true))
      ).awaitPlayback(firstSource)

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(secondSource))
    helper.answerHandleRequestWithData(secondSource, createSourceData(32768), Nil)

    val events = helper.fishForEvents {
      case e: RadioSourceErrorEvent => FishingOutcome.Complete
      case _ => FishingOutcome.Continue
    }
    val secondStartEventIdx = events.indexWhere {
      case RadioSourceChangedEvent(source, _) if source == secondSource => true
      case _ => false
    }
    val progressSources = filterType[RadioPlaybackProgressEvent](events.drop(secondStartEventIdx)).map(_.source)
    progressSources should not be empty
    progressSources should not contain firstSource

  it should "reset playback progress data when switching to another source" in :
    val source1 = RadioSource("source1.mp3")
    val source2 = RadioSource("source2.mp3")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(source1))
      .answerHandleRequestWithData(source1, createSourceData(16384), Nil)
      .fishForEvents {
        case _: RadioSourceErrorEvent => FishingOutcome.Complete
        case _ => FishingOutcome.ContinueAndIgnore
      }

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(source2))
      .answerHandleRequestWithData(source2, createSourceData(32768), Nil)
    val progressEvent = filterType[RadioPlaybackProgressEvent](
      helper.fishForEvents {
        case _: RadioPlaybackProgressEvent => FishingOutcome.Complete
        case _ => FishingOutcome.ContinueAndIgnore
      }
    ).head
    println(progressEvent)
    progressEvent.bytesProcessed should be < 9000L
    progressEvent.playbackTime should be(10929706.nanos)

  it should "suppress metadata events for the previous source after switching to a new one" in :
    val firstSource = RadioSource("metaFirst.mp3")
    val secondSource = RadioSource("metaSecond.mp3")
    val secondMetadata = List("meta2.1", "meta2.2", "meta2.3")
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(firstSource))
      .answerHandleRequest(
        firstSource,
        Success(createMockHandle(createSourceData(1024), List("meta1.1", "meta1.2"), cyclic = true))
      ).fishForEvents {
        // Wait for the arrival of a metadata event
        case e: RadioMetadataEvent => FishingOutcome.Complete
        case _ => FishingOutcome.ContinueAndIgnore
      }

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(secondSource))
    helper.answerHandleRequestWithData(secondSource, createSourceData(1024), secondMetadata)

    val events = helper.fishForEvents {
      case e: RadioSourceErrorEvent => FishingOutcome.Complete
      case _ => FishingOutcome.Continue
    }
    val secondStartEventIdx = events.indexWhere {
      case RadioSourceChangedEvent(source, _) if source == secondSource => true
      case _ => false
    }
    val expectedMetadata = secondMetadata.map(CurrentMetadata.apply)
    val receivedMetadata = filterType[RadioMetadataEvent](events.drop(secondStartEventIdx)).map(_.metadata)
    receivedMetadata should contain theSameElementsInOrderAs expectedMetadata

  it should "support stopping playback" in :
    val radioSource = RadioSource("toBeStopped.mp3")
    val handle = createMockHandle(createSourceData(8192), List("meta", "data"), cyclic = true)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequest(radioSource, Success(handle))
      .awaitPlayback(radioSource)

    val events = helper.sendCommand(RadioStreamPlaybackActor.StopPlayback)
      .fishForEvents {
        case e: RadioPlaybackStoppedEvent if e.source == radioSource => FishingOutcome.Complete
        case _ => FishingOutcome.Continue
      }
    filterType[RadioSourceErrorEvent](events) shouldBe empty
    verify(handle).cancelStream()

    // Verify that the stream was actually stopped by starting a new one.
    val nextSource = RadioSource("afterStop.mp3")
    val nextEvents = helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(nextSource))
      .answerHandleRequestWithData(nextSource, createSourceData(512), Nil)
      .fishForEvents {
        case e: RadioSourceErrorEvent if e.source == nextSource => FishingOutcome.Complete
        case _ => FishingOutcome.Continue
      }
    nextEvents.head match
      case e: RadioSourceChangedEvent =>
        e.source should be(nextSource)
      case e => fail("Unexpected event: " + e)

  it should "ignore a StopPlayback command if no source is currently playing" in :
    val radioSource = RadioSource("afterIgnoredStop.mp3")
    val helper = new PlaybackActorTestHelper

    val events = helper.sendCommand(RadioStreamPlaybackActor.StopPlayback)
      .sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequestWithData(radioSource, createSourceData(512), Nil)
      .fishForEvents {
        case e: RadioSourceErrorEvent if e.source == radioSource => FishingOutcome.Complete
        case _ => FishingOutcome.Continue
      }

    filterType[RadioPlaybackStoppedEvent](events) shouldBe empty

  it should "reset playback data after handling a StopPlayback command" in :
    val radioSource = RadioSource("toBeStoppedMulti.mp3")
    val handle = createMockHandle(createSourceData(8192), List("meta", "data"), cyclic = true)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .answerHandleRequest(radioSource, Success(handle))
      .awaitPlayback(radioSource)
      .sendCommand(RadioStreamPlaybackActor.StopPlayback)

    val nextSource = RadioSource("afterStop.mp3")
    val nextEvents = helper.sendCommand(RadioStreamPlaybackActor.StopPlayback)
      .sendCommand(RadioStreamPlaybackActor.PlayRadioSource(nextSource))
      .answerHandleRequestWithData(nextSource, createSourceData(512), Nil)
      .fishForEvents {
        case e: RadioSourceErrorEvent if e.source == nextSource => FishingOutcome.Complete
        case _ => FishingOutcome.Continue
      }
    filterType[RadioPlaybackStoppedEvent](nextEvents) should have size 1
    verify(handle, times(1)).cancelStream()

  it should "handle race conditions with stopping playback before it fully started" in :
    val radioSource = RadioSource("stoppedAbruptly.mp3")
    val handle = createMockHandle(createSourceData(4096), List("some", "meta", "data"), cyclic = true)
    val helper = new PlaybackActorTestHelper

    helper.sendCommand(RadioStreamPlaybackActor.PlayRadioSource(radioSource))
      .sendCommand(RadioStreamPlaybackActor.StopPlayback)
      .answerHandleRequest(radioSource, Success(handle))

    val events = helper.fishForEvents {
      case e: RadioPlaybackStoppedEvent => FishingOutcome.Complete
      case _ => FishingOutcome.Continue
    }
    filterType[RadioSourceChangedEvent](events).map(_.source) should contain only radioSource
    filterType[RadioPlaybackStoppedEvent](events).map(_.source) should contain only radioSource
    verify(handle, timeout(3000)).cancelStream()

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
      * Expects a request to the handle actor for a source contained in the
      * specified map. This function can be used if it is unclear which
      * requests will actually arrive, since sources may already have been
      * terminated before they start.
      *
      * @param sources the map with handle data for the possible sources
      * @return the requested source
      */
    def answerAnyHandleRequest(sources: Map[RadioSource, SourceHandleData]): RadioSource =
      val request = probeHandleActor.expectMessageType[RadioStreamHandleManagerActor.GetStreamHandle]
      request.params.streamName should be(RadioStreamPlaybackActor.PlaybackStreamName)
      val handleData = sources(request.params.streamSource)
      val response = RadioStreamHandleManagerActor.GetStreamHandleResponse(
        source = request.params.streamSource,
        triedStreamHandle = handleData.triedHandle,
        optLastMetadata = handleData.optLastMetadata
      )
      request.replyTo ! response
      request.params.streamSource

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
      val dataMap = Map(expectedSource -> SourceHandleData(triedHandle, optMetadata))
      answerAnyHandleRequest(dataMap)
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
      * Waits for the arrival of a playback progress event for the given source
      * to make sure that playback is actually ongoing.
      *
      * @param source the expected radio source
      * @return this test helper
      */
    def awaitPlayback(source: RadioSource): PlaybackActorTestHelper =
      fishForEvents {
        case e: RadioPlaybackProgressEvent if e.source == source => FishingOutcome.Complete
        case _ => FishingOutcome.ContinueAndIgnore
      }
      this

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
