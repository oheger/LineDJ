/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamTestHelper.{ChunkSize, FailingStream, MonitoringStream, TestDataGeneratorStream}
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito.{timeout, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.{ByteArrayInputStream, InputStream}
import java.time.{Duration, LocalDateTime}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object RadioStreamActorSpec {
  /** URI pointing to an audio stream. */
  private val AudioStreamUri = "music.mp3"

  /** URI pointing to a m3u file. */
  private val PlaylistStreamUri = "playlist.m3u"

  /** A stream reference representing the audio stream. */
  private val AudioStreamRef = StreamReference(AudioStreamUri)

  /** A stream reference representing the m3u file to be resolved. */
  private val PlaylistStreamRef = StreamReference(PlaylistStreamUri)

  /** Constant for the size of the managed buffer. */
  private val BufferSize = 16384

  /** Test configuration to be used by tests. */
  private val Config = createConfig()

  /**
    * Creates test configuration.
    *
    * @return the configuration
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(mediaManagerActor = null, actorCreator = null,
      bufferChunkSize = ChunkSize, inMemoryBufferSize = BufferSize)
}

/**
  * Test class for [[RadioStreamActor]].
  */
class RadioStreamActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("RadioStreamActorSpec"))

  /** The test kit for typed actors. */
  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    testKit.shutdownTestKit()
    super.afterAll()
  }

  import RadioStreamActorSpec._

  /**
    * Expects that the given actor gets terminated.
    *
    * @param actor the actor in question
    */
  private def expectTermination(actor: ActorRef): Unit = {
    val probe = TestProbe()
    probe watch actor
    probe.expectTerminated(actor)
  }

  "RadioStreamActor" should "resolve the stream reference" in {
    val helper = new StreamActorTestHelper

    helper.createTestActor(AudioStreamRef)

    helper.verifyM3uResolveOperation(AudioStreamRef)
  }

  it should "stop itself if resolving of the audio stream fails" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    helper.setM3uResolveResult(Failure(new IllegalStateException("Failure")))
    expectTermination(actor)
  }

  it should "notify the source listener when the audio stream has been resolved" in {
    val helper = new StreamActorTestHelper
    helper.createTestActor(PlaylistStreamRef)
    helper.setM3uResolveResult(Success(AudioStreamRef))

    helper.probeSourceListener.expectMsg(AudioSource(AudioStreamUri, Long.MaxValue, 0, 0))
  }

  it should "create its own M3uReader if necessary" in {
    val probeListener = TestProbe()
    val probeEventActor = testKit.createTestProbe[RadioEvent]()
    system.actorOf(RadioStreamActor(Config, StreamReference(AudioStreamUri), probeListener.ref, probeEventActor.ref))

    probeListener.expectMsg(AudioSource(AudioStreamUri, Long.MaxValue, 0, 0))
  }

  it should "fill its internal buffer" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)

    helper.createTestActor(AudioStreamRef)
    stream.expectReadsUntil(BufferSize)
  }

  it should "stop reading when the internal buffer is full" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)

    helper.createTestActor(AudioStreamRef)
    stream.expectReadsUntil(BufferSize)
    awaitCond(stream.readQueue.poll(50, TimeUnit.MILLISECONDS) == null)

    // The exact amount of read bytes depends on the internal chunk size used by Akka Http.
    stream.bytesCount.get() should be < 3L * BufferSize
  }

  it should "allow reading data from the buffer" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)
    val actor = helper.createTestActor(AudioStreamRef)
    stream.expectReadsUntil(ChunkSize)

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    actor ! PlaybackActor.GetAudioData(ChunkSize)

    val msg1 = expectMsgType[BufferDataResult]
    msg1.data.length should be(ChunkSize)
    msg1.data.toArray should be(RadioStreamTestHelper.refData(ChunkSize))
    val msg2 = expectMsgType[BufferDataResult]
    msg2.data.length should be(ChunkSize)
    msg2.data.toArray should be(RadioStreamTestHelper.refData(ChunkSize, skipChunks = 1))
  }

  it should "fill the buffer again when data has been read" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)
    val actor = helper.createTestActor(AudioStreamRef)
    stream.expectReadsUntil(BufferSize)

    actor ! PlaybackActor.GetAudioData(ChunkSize)

    expectMsgType[BufferDataResult]
    stream.expectRead().requestSize should be(ChunkSize)
  }

  it should "handle reads before the stream is initialized" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    helper.setM3uResolveResult(Success(AudioStreamRef))
    helper.initRadioStream()

    val msg = expectMsgType[BufferDataResult]
    msg.data.toArray should be(RadioStreamTestHelper.refData(ChunkSize))
  }

  it should "handle a close request by closing the stream" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)
    val actor = helper.createTestActor(AudioStreamRef)
    stream.expectRead()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    awaitCond(stream.closed.get() == 1)
  }

  it should "answer data requests in closing state with an EoF message" in {
    val helper = new StreamActorTestHelper
    helper.initRadioStream()
    val actor = helper.createTestActor(AudioStreamRef)
    actor ! CloseRequest
    expectMsg(CloseAck(actor))

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    expectMsg(BufferDataComplete)
  }

  it should "handle an AudioSourceResolved message in closing state" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    helper.setM3uResolveResult(Success(AudioStreamRef))
    helper.waitForStreamLoaderRequest()
    actor ! CloseRequest
    helper.initRadioStream()

    expectMsg(CloseAck(actor))
  }

  it should "stop itself if the managed stream terminates unexpectedly" in {
    val helper = new StreamActorTestHelper
    helper.initRadioStream(new ByteArrayInputStream(FileTestHelper.testBytes()))
    val actor = helper.createTestActor(AudioStreamRef)

    expectTermination(actor)
  }

  it should "stop itself if the audio stream cannot be resolved" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(PlaylistStreamRef)

    helper.setM3uResolveResult(Success(AudioStreamRef))
    helper.initStreamLoaderResponse(Failure(new IllegalStateException("Test exception")))

    expectTermination(actor)
  }

  it should "stop itself if the managed stream throws an exception" in {
    val helper = new StreamActorTestHelper
    helper.initRadioStream(new FailingStream)

    val actor = helper.createTestActor(AudioStreamRef)

    expectTermination(actor)
  }

  it should "handle a radio stream with metadata" in {
    val AudioChunkSize = 128
    val ChunkCount = 256
    val ChunksToRead = 5
    val MetadataContent = "0123456789ABCDEF"
    val metadataBlock = RadioStreamTestHelper.metadataBlock(ByteString(MetadataContent))
    val chunks = (0 until ChunkCount) map { idx =>
      RadioStreamTestHelper.dataBlock(AudioChunkSize, idx) ++ metadataBlock
    }
    val entitySource = Source(chunks)
    val response = HttpResponse(headers = List(RawHeader("icy-metaint", AudioChunkSize.toString)),
      entity = HttpEntity(ContentTypes.`application/octet-stream`, entitySource))
    val expectedData = ByteString(RadioStreamTestHelper.refData(ChunksToRead * AudioChunkSize))
    val helper = new StreamActorTestHelper
    helper.initStreamLoaderResponse(Success(response))

    val actor = helper.createTestActor(AudioStreamRef)
    val audioData = (1 to ChunksToRead).foldLeft[ByteString](ByteString.empty) { (data, _) =>
      actor ! PlaybackActor.GetAudioData(AudioChunkSize)
      data ++ expectMsgType[BufferDataResult].data
    }

    audioData.utf8String should be(expectedData.utf8String)

    val expMetadata = CurrentMetadata(MetadataContent)
    helper.expectMetadataEvent(expMetadata)
  }

  it should "handle a radio stream with an invalid audio chunk size header" in {
    val entitySource = RadioStreamTestHelper.createSourceFromStream()
    val response = HttpResponse(headers = List(RawHeader("icy-metaint", "invalid header value")),
      entity = HttpEntity(ContentTypes.`application/octet-stream`, entitySource))
    val helper = new StreamActorTestHelper
    helper.initStreamLoaderResponse(Success(response))

    val actor = helper.createTestActor(AudioStreamRef)
    actor ! PlaybackActor.GetAudioData(RadioStreamTestHelper.ChunkSize)
    val data = expectMsgType[BufferDataResult].data

    data should be(RadioStreamTestHelper.refData(RadioStreamTestHelper.ChunkSize))
  }

  it should "generate a MetadataNotSupported event if the stream does not support metadata" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)

    helper.createTestActor(AudioStreamRef)

    helper.expectMetadataEvent(MetadataNotSupported)
  }

  /**
    * A test helper class that manages a test actor instance and its
    * dependencies.
    */
  private class StreamActorTestHelper {
    /** Test probe for an audio source listener actor. */
    val probeSourceListener: TestProbe = TestProbe()

    /** The promise for the future returned by the M3uReader mock. */
    private val m3uPromise = Promise[StreamReference]()

    /** The promise for the future returned by the mock HTTP stream loader. */
    private val audioStreamPromise = Promise[HttpResponse]()

    /**
      * A flag that indicates when the stream loader has been triggered to load
      * the radio stream.
      */
    private val audioStreamRequested = new AtomicBoolean

    /** A mock for the object that resolves m3u URIs. */
    private val m3uReader = createM3uReader()

    /** A mock for the object that loads streams via HTTP. */
    private val httpLoader = createHttpLoader()

    /** Test probe for the event publisher actor. */
    private val probeEventActor = testKit.createTestProbe[RadioEvent]()

    def createTestActor(streamRef: StreamReference): ActorRef = {
      val props = stream.RadioStreamActor(Config, streamRef, probeSourceListener.ref, probeEventActor.ref, Some(m3uReader),
        Some(httpLoader))
      system.actorOf(props)
    }

    /**
      * Checks whether the M3u reader was invoked to resolve the given stream
      * reference.
      *
      * @param streamRef the expected reference
      * @return this test helper
      */
    def verifyM3uResolveOperation(streamRef: StreamReference): StreamActorTestHelper = {
      verify(m3uReader, timeout(1000)).resolveAudioStream(argEq(Config), argEq(streamRef))(any(), any())
      this
    }

    /**
      * Sets the result produced by the M3u reader.
      *
      * @param result the result
      * @return this test helper
      */
    def setM3uResolveResult(result: Try[StreamReference]): StreamActorTestHelper = {
      result match {
        case Failure(exception) => m3uPromise.failure(exception)
        case Success(value) => m3uPromise.success(value)
      }
      this
    }

    /**
      * Defines the audio stream to be returned by the mock stream loader.
      *
      * @param stream the stream with the content of the radio stream
      */
    def initRadioStream(stream: InputStream = new TestDataGeneratorStream): Unit = {
      val entitySource = RadioStreamTestHelper.createSourceFromStream(stream)
      val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, entitySource))
      initStreamLoaderResponse(Success(response))
    }

    /**
      * Defines the return value of the stream loader when it is triggered to
      * load the audio stream.
      *
      * @param triedResponse the response to return from the loader
      */
    def initStreamLoaderResponse(triedResponse: Try[HttpResponse]): Unit = {
      audioStreamPromise.complete(triedResponse)
    }

    /**
      * Waits until the stream loader was triggered to load the radio stream.
      * This can be used to simulate special situations.
      */
    def waitForStreamLoaderRequest(): Unit = {
      awaitCond(audioStreamRequested.get())
    }

    /**
      * Checks whether a metadata event with the expected content was passed to
      * the event actor.
      *
      * @param metadata the expected metadata
      */
    def expectMetadataEvent(metadata: RadioMetadata): Unit = {
      val metadataEvent = probeEventActor.expectMessageType[RadioMetadataEvent]
      metadataEvent.metadata should be(metadata)
      val timeDelta = Duration.between(metadataEvent.time, LocalDateTime.now())
      timeDelta.toSeconds should be < 5L
    }

    /**
      * Creates a mock for an ''M3uReader'' and configures it to expect a
      * resolve operation. If a the reference to be resolved points to a
      * playlist, the mock returns a promise, and the result can be set later
      * via ''setM3uResolveResult()''. Otherwise, the mock returns a successful
      * future with the same reference.
      *
      * @return the mock M3u reader
      */
    private def createM3uReader(): M3uReader = {
      val reader = mock[M3uReader]
      when(reader.resolveAudioStream(any(), any())(any(), any()))
        .thenAnswer((invocation: InvocationOnMock) => invocation.getArgument[StreamReference](1) match {
          case StreamReference(uri) if uri.endsWith(".m3u") => m3uPromise.future
          case ref: StreamReference => Future.successful(ref)
        })
      reader
    }

    /**
      * Creates a mock for an ''HttpStreamLoader'' and prepares it to expect an
      * operation to resolve the audio stream. The mock returns a future,
      * which can be set later via the [[initRadioStream]] function.
      *
      * @return the mock stream loader
      */
    private def createHttpLoader(): HttpStreamLoader = {
      val expectedRequest = HttpRequest(uri = AudioStreamUri, headers = List(RawHeader("Icy-MetaData", "1")))
      val loader = mock[HttpStreamLoader]
      when(loader.sendRequest(expectedRequest)).thenAnswer((_: InvocationOnMock) => {
        audioStreamRequested.set(true)
        audioStreamPromise.future
      })
      loader
    }
  }
}
