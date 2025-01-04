/*
 * Copyright 2015-2025 The Developers Team.
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

import de.oliver_heger.linedj.player.engine.radio.stream.RadioStreamHandle.SinkType
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.KillSwitch
import org.apache.pekko.stream.scaladsl.{RunnableGraph, Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.{ByteString, Timeout}
import org.apache.pekko.{Done, NotUsed}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Succeeded, TryValues}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{TimeUnit, *}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object RadioStreamHandleSpec:
  /** The default playback buffer size for radio streams. */
  private val BufferSize = 77

  /** URL of a test radio stream. */
  private val RadioStreamUri = "https://example.com/radio.m3u"

  /** The chunk count when generating a test radio stream. */
  private val RadioStreamChunkCount = 16

  /** The expected audio data that is generated for the test stream. */
  private val ExpectedAudioChunks = (0 until RadioStreamChunkCount).foldLeft(ByteString.empty) { (str, idx) =>
    val block = RadioStreamTestHelper.dataBlock(RadioStreamTestHelper.AudioChunkSize, idx)
    str ++ block
  }

  /** The expected metadata that is generated for the test stream. */
  private val ExpectedMetadata = (1 to RadioStreamChunkCount).map(RadioStreamTestHelper.generateMetadata)

  /**
    * Returns a resolved URI for the given stream URI. This is used to
    * simulate a resolving mechanism.
    *
    * @param streamUri the original stream URI
    * @return the resolved stream URI
    */
  private def resolvedStreamUri(streamUri: String): String =
    streamUri.replace(".com/", ".com/resolved/")
      .replace(".m3u", ".mp3")
end RadioStreamHandleSpec

/**
  * Test class for [[RadioStreamHandle]].
  */
class RadioStreamHandleSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with TryValues with MockitoSugar:
  def this() = this(ActorSystem("RadioStreamHandleSpec"))

  /** A test kit for testing typed actors. */
  private val typedTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    typedTestKit.shutdownTestKit()
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import RadioStreamHandleSpec.*

  /**
    * Creates a mock [[RadioStreamBuilder]] that is prepared to construct a
    * radio stream with the given parameters.
    *
    * @param streamUri the URI for the stream
    * @param source    the source for the stream
    * @return the mock builder for creating this stream
    */
  private def createMockStreamBuilder(streamUri: String, source: Source[ByteString, NotUsed]): RadioStreamBuilder =
    val streamBuilder = mock[RadioStreamBuilder]
    when(streamBuilder.buildRadioStream(any())(any(), any())).thenAnswer((invocation: InvocationOnMock) =>
      val params = invocation.getArgument[RadioStreamBuilder.RadioStreamParameters[SinkType, SinkType]](0)
      params.streamUri should be(streamUri)
      params.bufferSize should be(BufferSize)
      val (graph, killSwitch) =
        RadioStreamBuilder.createGraphForSource(source, params, Some(RadioStreamTestHelper.AudioChunkSize))
      val result = RadioStreamBuilder.BuilderResult(
        resolvedUri = resolvedStreamUri(streamUri),
        graph = graph,
        killSwitch = killSwitch,
        metadataSupported = true
      )
      Future.successful(result))
    streamBuilder

  /**
    * Creates a [[CountDownLatch]] that gets triggered when the given
    * [[Future]] completes. This can be used to check whether completion
    * happens within a specific time frame.
    *
    * @param future the future to check
    * @return the latch triggered by the future
    */
  private def createLatchForFutureCompletion(future: Future[Unit]): CountDownLatch =
    given ExecutionContext = system.dispatcher

    val latch = new CountDownLatch(1)
    future.onComplete(_ => latch.countDown())
    latch

  /**
    * Creates a [[Sink]] that populates the given queue.
    *
    * @param queue the queue
    * @return the sink for writing data into the queue
    */
  private def createSinkForQueue(queue: BlockingQueue[ByteString]): Sink[ByteString, Future[Done]] =
    Sink.foreach(queue.offer)

  /**
    * Reads metadata from the given queue.
    *
    * @param queue   the queue to read data from
    * @param current the metadata that was already read
    * @return a set with the metadata strings read from the queue
    */
  @tailrec private def readMetadataChunks(queue: BlockingQueue[ByteString], current: Set[ByteString]):
  Set[ByteString] =
    if queue.isEmpty then current
    else readMetadataChunks(queue, current + queue.poll())

  /**
    * Reads audio data from the given queue. Since the audio source is cyclic,
    * only a certain number of chunks is read. This should be sufficient to
    * verify whether the correct data is received.
    *
    * @param queue   the queue to read data from
    * @param current the audio data that was already read
    * @return a string with the audio data that has been read
    */
  @tailrec private def readAudioChunks(queue: BlockingQueue[ByteString], current: ByteString): ByteString =
    if current.length >= 3 * RadioStreamChunkCount * RadioStreamTestHelper.AudioChunkSize then
      current
    else
      val chunk = queue.poll(3, TimeUnit.SECONDS)
      chunk should not be null
      readAudioChunks(queue, current ++ chunk)

  "RadioStreamHandle" should "create an instance using a builder" in :
    val streamData = RadioStreamTestHelper.generateAudioDataWithMetadata(RadioStreamChunkCount,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource = Source.cycle(() => streamData.iterator)
    val streamBuilder = createMockStreamBuilder(RadioStreamUri, radioDataSource)

    RadioStreamHandle.factory.create(streamBuilder, RadioStreamUri, BufferSize) flatMap { handle =>
      handle.builderResult.resolvedUri should be(resolvedStreamUri(RadioStreamUri))
      handle.builderResult.metadataSupported shouldBe true

      handle.attachOrCancel() flatMap { (sourceAudio, sourceMeta) =>
        val audioDataQueue = new LinkedBlockingQueue[ByteString]
        val audioDataSink = createSinkForQueue(audioDataQueue)
        val futAudioDataSink = sourceAudio.runWith(audioDataSink)
        val metadataQueue = new LinkedBlockingQueue[ByteString]
        val metadataSink = createSinkForQueue(metadataQueue)
        val futMetadataSink = sourceMeta.runWith(metadataSink)

        val audioChunks = readAudioChunks(audioDataQueue, ByteString.empty)
        awaitCond(metadataQueue.size() >= RadioStreamChunkCount)

        handle.cancelStream()
        val futSinks = for
          _ <- futAudioDataSink
          _ <- futMetadataSink
        yield Done

        futSinks map { _ =>
          readMetadataChunks(metadataQueue, Set.empty)
            .map(_.utf8String) should contain theSameElementsAs ExpectedMetadata
          audioChunks.utf8String should include(ExpectedAudioChunks.utf8String)
        }
      }
    }

  it should "support attaching only to the audio data source" in :
    val streamData = RadioStreamTestHelper.generateAudioDataWithMetadata(RadioStreamChunkCount,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource = Source.cycle(() => streamData.iterator)
    val streamBuilder = createMockStreamBuilder(RadioStreamUri, radioDataSource)

    RadioStreamHandle.factory.create(streamBuilder, RadioStreamUri, BufferSize) flatMap { handle =>
      handle.attachAudioSink() flatMap { sourceAudio =>
        val audioDataQueue = new LinkedBlockingQueue[ByteString]
        val audioDataSink = createSinkForQueue(audioDataQueue)
        val futAudioDataSink = sourceAudio.runWith(audioDataSink)

        val audioChunks = readAudioChunks(audioDataQueue, ByteString.empty)

        handle.cancelStream()

        futAudioDataSink map { _ =>
          audioChunks.utf8String should include(ExpectedAudioChunks.utf8String)
        }
      }
    }

  it should "support attaching only to the metadata source" in :
    val streamData = RadioStreamTestHelper.generateAudioDataWithMetadata(RadioStreamChunkCount,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource = Source.cycle(() => streamData.iterator)
    val streamBuilder = createMockStreamBuilder(RadioStreamUri, radioDataSource)

    RadioStreamHandle.factory.create(streamBuilder, RadioStreamUri, BufferSize) flatMap { handle =>
      handle.attachMetadataSink() flatMap { sourceMeta =>
        val metadataQueue = new LinkedBlockingQueue[ByteString]
        val metadataSink = createSinkForQueue(metadataQueue)

        val futMetadataSink = sourceMeta.runWith(metadataSink)
        awaitCond(metadataQueue.size() >= RadioStreamChunkCount)
        handle.cancelStream()

        futMetadataSink map { _ =>
          readMetadataChunks(metadataQueue, Set.empty)
            .map(_.utf8String) should contain theSameElementsAs ExpectedMetadata
        }
      }
    }

  it should "support to detach from the radio stream sinks" in :
    val controlAudio = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val controlMeta = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val handle = RadioStreamHandle(
      controlAudio.ref,
      controlMeta.ref,
      mock[RadioStreamBuilder.BuilderResult[SinkType, SinkType]],
      null
    )

    handle.detach()

    val ctrlAudioMsg = controlAudio.expectMessageType[AttachableSink.AttachableSinkControlCommand[ByteString]]
    ctrlAudioMsg should be(AttachableSink.DetachConsumer())
    val ctrlMetaMsg = controlMeta.expectMessageType[AttachableSink.AttachableSinkControlCommand[ByteString]]
    ctrlMetaMsg should be(AttachableSink.DetachConsumer())

  it should "set unique names to support multiple streams in parallel" in :
    val RadioStreamUri1 = "https://radio.example.com/foo.mp3"
    val streamData1 = RadioStreamTestHelper.generateAudioDataWithMetadata(32,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource1 = Source.cycle(() => streamData1.iterator)
    val streamBuilder1 = createMockStreamBuilder(RadioStreamUri1, radioDataSource1)
    val RadioStreamUri2 = "https://radio.example.com/bar.m3u"
    val streamData2 = RadioStreamTestHelper.generateAudioDataWithMetadata(8,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource2 = Source.cycle(() => streamData2.iterator)
    val streamBuilder2 = createMockStreamBuilder(RadioStreamUri2, radioDataSource2)

    val futHandles = for
      handle1 <- RadioStreamHandle.factory.create(streamBuilder1, RadioStreamUri1, BufferSize)
      handle2 <- RadioStreamHandle.factory.create(streamBuilder2, RadioStreamUri2, BufferSize, "stream2")
    yield (handle1, handle2)
    futHandles map { (handle1, handle2) =>
      handle1.cancelStream()
      handle2.cancelStream()
      Succeeded
    }

  it should "not complete the future for the completed stream before the stream completes" in :
    val RadioStreamUri = "https://example.com/ongoing-radio.m3u"
    val RadioStreamChunkCount = 16
    val streamData = RadioStreamTestHelper.generateAudioDataWithMetadata(RadioStreamChunkCount,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource = Source.cycle(() => streamData.iterator)
    val streamBuilder = createMockStreamBuilder(RadioStreamUri, radioDataSource)

    RadioStreamHandle.factory.create(streamBuilder, RadioStreamUri, BufferSize) map { handle =>
      val latch = createLatchForFutureCompletion(handle.futStreamDone)
      val completed = latch.await(100, TimeUnit.MILLISECONDS)
      handle.cancelStream()
      completed shouldBe false
    }

  it should "complete the future indicating stream completion" in :
    val RadioStreamUri = "https://example.com/completed-radio.m3u"
    val RadioStreamChunkCount = 16
    val streamData = RadioStreamTestHelper.generateAudioDataWithMetadata(RadioStreamChunkCount,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource = Source.cycle(() => streamData.iterator)
    val streamBuilder = createMockStreamBuilder(RadioStreamUri, radioDataSource)

    RadioStreamHandle.factory.create(streamBuilder, RadioStreamUri, BufferSize, "notifyStream") map { handle =>
      val latch = createLatchForFutureCompletion(handle.futStreamDone)
      handle.cancelStream()
      println("Checking for completion of future.")
      val completed = latch.await(3, TimeUnit.SECONDS)
      completed shouldBe true
    }

  it should "cancel the stream if an attach operation fails" in :
    val killSwitch = mock[KillSwitch]
    val controlAudio = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val controlMeta = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val builderResult = RadioStreamBuilder.BuilderResult(
      resolvedUri = "someStreamUri",
      graph = mock[RunnableGraph[(RadioStreamHandle.SinkType, RadioStreamHandle.SinkType)]],
      killSwitch = killSwitch,
      metadataSupported = false
    )
    val handle = RadioStreamHandle(
      controlAudio.ref,
      controlMeta.ref,
      builderResult,
      null
    )

    recoverToSucceededIf[TimeoutException] {
      handle.attachOrCancel(Timeout(1.millis))
    } map { _ =>
      verify(killSwitch).shutdown()
      Succeeded
    }

  it should "cancel the stream if an attach operation for audio data fails" in :
    val killSwitch = mock[KillSwitch]
    val controlAudio = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val controlMeta = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val builderResult = RadioStreamBuilder.BuilderResult(
      resolvedUri = "someStreamUri",
      graph = mock[RunnableGraph[(RadioStreamHandle.SinkType, RadioStreamHandle.SinkType)]],
      killSwitch = killSwitch,
      metadataSupported = false
    )
    val handle = RadioStreamHandle(
      controlAudio.ref,
      controlMeta.ref,
      builderResult,
      null
    )

    recoverToSucceededIf[TimeoutException] {
      handle.attachAudioSinkOrCancel(Timeout(1.millis))
    } map { _ =>
      verify(killSwitch).shutdown()
      Succeeded
    }

  it should "cancel the stream if an attach operation for metadata fails" in :
    val killSwitch = mock[KillSwitch]
    val controlAudio = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val controlMeta = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val builderResult = RadioStreamBuilder.BuilderResult(
      resolvedUri = "someStreamUri",
      graph = mock[RunnableGraph[(RadioStreamHandle.SinkType, RadioStreamHandle.SinkType)]],
      killSwitch = killSwitch,
      metadataSupported = false
    )
    val handle = RadioStreamHandle(
      controlAudio.ref,
      controlMeta.ref,
      builderResult,
      null
    )

    recoverToSucceededIf[TimeoutException] {
      handle.attachMetadataSinkOrCancel(Timeout(1.millis))
    } map { _ =>
      verify(killSwitch).shutdown()
      Succeeded
    }
