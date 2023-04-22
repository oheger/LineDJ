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
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import akka.{Done, NotUsed}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor
import de.oliver_heger.linedj.player.engine.radio._
import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamTestHelper.{ChunkSize, FailingStream, MonitoringStream, TestDataGeneratorStream}
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.{ByteArrayInputStream, InputStream}
import java.time.{Duration, LocalDateTime}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}

object RadioStreamActorSpec {
  /** URI pointing to an audio stream. */
  private val AudioStreamUri = "music.mp3"

  /** URI pointing to a m3u file. */
  private val PlaylistStreamUri = "playlist.m3u"

  /** The radio source passed to test actor instances. */
  private val TestRadioSource = RadioSource(PlaylistStreamUri)

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

  "RadioStreamActor" should "notify the source listener when the audio stream has been resolved" in {
    val helper = new StreamActorTestHelper
    helper.createTestActor()
    helper.initRadioStream()

    helper.probeSourceListener.expectMsg(AudioSource(AudioStreamUri, Long.MaxValue, 0, 0))
  }

  it should "fill its internal buffer" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)

    helper.createTestActor()
    stream.expectReadsUntil(BufferSize)
  }

  it should "stop reading when the internal buffer is full" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)

    helper.createTestActor()
    stream.expectReadsUntil(BufferSize)
    awaitCond(stream.readQueue.poll(50, TimeUnit.MILLISECONDS) == null)

    // The exact amount of read bytes depends on the internal chunk size used by Akka Http.
    stream.bytesCount.get() should be < 3L * BufferSize
  }

  it should "allow reading data from the buffer" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)
    val actor = helper.createTestActor()
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
    val actor = helper.createTestActor()
    stream.expectReadsUntil(BufferSize)

    actor ! PlaybackActor.GetAudioData(ChunkSize)

    expectMsgType[BufferDataResult]
    stream.expectRead().requestSize should be(ChunkSize)
  }

  it should "handle reads before the stream is initialized" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor()

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    helper.initRadioStream()

    val msg = expectMsgType[BufferDataResult]
    msg.data.toArray should be(RadioStreamTestHelper.refData(ChunkSize))
  }

  it should "handle a close request by closing the stream and stopping itself" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)
    val actor = helper.createTestActor()
    stream.expectRead()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    awaitCond(stream.closed.get() == 1)
    expectTermination(actor)
  }

  it should "answer data requests in closing state with an EoF message" in {
    val helper = new StreamActorTestHelper
    helper.initRadioStream()
    val actor = helper.createTestActor()
    actor ! CloseRequest
    actor ! PlaybackActor.GetAudioData(ChunkSize)

    expectMsg(BufferDataComplete)
    expectMsg(CloseAck(actor))
  }

  it should "handle a the stream builder result in closing state" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor()

    helper.waitForStreamBuilderRequest()
    actor ! CloseRequest
    helper.initRadioStream()

    expectMsg(CloseAck(actor))
  }

  it should "stop itself if the managed stream terminates unexpectedly" in {
    val helper = new StreamActorTestHelper
    helper.initRadioStream(new ByteArrayInputStream(FileTestHelper.testBytes()))
    val actor = helper.createTestActor()

    expectTermination(actor)
  }

  it should "stop itself if the audio stream cannot be resolved" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor()

    helper.failRadioStream(new IllegalStateException("Test exception"))

    expectTermination(actor)
  }

  it should "stop itself if the managed stream throws an exception" in {
    val helper = new StreamActorTestHelper
    helper.initRadioStream(new FailingStream)

    val actor = helper.createTestActor()

    expectTermination(actor)
  }

  it should "handle a radio stream with metadata" in {
    val ChunkCount = 256
    val StreamChunkSize = 128
    val ChunksToRead = 5
    val entitySource = RadioStreamTestHelper.generateRadioStreamSource(ChunkCount, StreamChunkSize)
    val expectedData = ByteString(RadioStreamTestHelper.refData(ChunksToRead * StreamChunkSize))
    val helper = new StreamActorTestHelper
    helper.initRadioStreamFromSource(entitySource, metadataSupport = true)
    val actor = helper.createTestActor()

    @tailrec def readAudioData(current: ByteString): ByteString =
      if (current.length > expectedData.length) current
      else {
        actor ! PlaybackActor.GetAudioData(RadioStreamTestHelper.AudioChunkSize)
        readAudioData(current ++ expectMsgType[BufferDataResult].data)
      }

    val audioData = readAudioData(ByteString.empty)
    audioData.utf8String should startWith(expectedData.utf8String)

    (1 to 2) foreach { index =>
      val expMetadata = CurrentMetadata(RadioStreamTestHelper.generateMetadata(index))
      helper.expectMetadataEvent(expMetadata)
    }
  }

  it should "generate a MetadataNotSupported event if the stream does not support metadata" in {
    val stream = new MonitoringStream
    val helper = new StreamActorTestHelper
    helper.initRadioStream(stream)

    helper.createTestActor()

    helper.expectMetadataEvent(MetadataNotSupported)
  }

  /**
    * A test helper class that manages a test actor instance and its
    * dependencies.
    */
  private class StreamActorTestHelper {
    /** Test probe for an audio source listener actor. */
    val probeSourceListener: TestProbe = TestProbe()

    /**
      * A promise for storing information about the source of the test radio
      * stream. From this information, the mock stream builder derives its
      * result. The promise stores a tuple with the stream's source and the
      * optional audio chunk size (in case metadata is supported).
      */
    private val radioSourcePromise = Promise[(Source[ByteString, Any], Option[Int])]()

    /**
      * A flag that indicates when the stream loader has been triggered to load
      * the radio stream.
      */
    private val audioStreamRequested = new AtomicBoolean

    /** A mock for the stream builder */
    private val streamBuilder = createStreamBuilder()

    /** Test probe for the event publisher actor. */
    private val probeEventActor = testKit.createTestProbe[RadioEvent]()

    /**
      * Creates a test actor instance.
      *
      * @return the test actor
      */
    def createTestActor(): ActorRef = {
      val props = stream.RadioStreamActor(Config,
        TestRadioSource,
        probeSourceListener.ref,
        probeEventActor.ref,
        streamBuilder)
      system.actorOf(props)
    }

    /**
      * Defines the source of the audio stream to be returned by the mock
      * stream builder.
      *
      * @param source          the source of the stream
      * @param metadataSupport flag whether metadata should be supported
      */
    def initRadioStreamFromSource(source: Source[ByteString, Any], metadataSupport: Boolean = false): Unit = {
      val optChunkSize = if (metadataSupport) Some(RadioStreamTestHelper.AudioChunkSize) else None
      radioSourcePromise.success((source, optChunkSize))
    }

    /**
      * Defines the audio stream to be returned by the mock stream builder.
      *
      * @param stream the stream with the content of the radio stream
      */
    def initRadioStream(stream: InputStream = new TestDataGeneratorStream): Unit = {
      initRadioStreamFromSource(RadioStreamTestHelper.createSourceFromStream(stream))
    }

    /**
      * Sets a failure result for the mock stream builder.
      *
      * @param exception the cause of the failure
      */
    def failRadioStream(exception: Throwable): Unit = {
      radioSourcePromise.failure(exception)
    }

    /**
      * Waits until the stream builder was triggered to load the radio stream.
      * This can be used to simulate special situations.
      */
    def waitForStreamBuilderRequest(): Unit = {
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
      metadataEvent.source should be(TestRadioSource)
      metadataEvent.metadata should be(metadata)
      val timeDelta = Duration.between(metadataEvent.time, LocalDateTime.now())
      timeDelta.toSeconds should be < 5L
    }

    /**
      * Creates a mock for a stream builder that is prepared return a stream
      * with the provided sinks that uses the source set via
      * ''initRadioStream()'' as input.
      *
      * @return the mock for the stream builder
      */
    private def createStreamBuilder(): RadioStreamBuilder = {
      val builder = mock[RadioStreamBuilder]
      when(builder.buildRadioStream(argEq(Config), argEq(PlaylistStreamUri), any(), any())(any(), any()))
        .thenAnswer((invocation: InvocationOnMock) => {
          import system.dispatcher
          audioStreamRequested.set(true)
          val sinkAudio = invocation.getArgument[Sink[ByteString, NotUsed]](2)
          val sinkMeta = invocation.getArgument[Sink[ByteString, Future[Done]]](3)
          radioSourcePromise.future.map { sourceData =>
            val (graph, kill) = RadioStreamBuilder.createGraphForSource(sourceData._1, Config, sinkAudio,
              sinkMeta, sourceData._2)
            RadioStreamBuilder.BuilderResult(AudioStreamUri, graph, kill, sourceData._2.isDefined)
          }
        })
      builder
    }
  }
}
