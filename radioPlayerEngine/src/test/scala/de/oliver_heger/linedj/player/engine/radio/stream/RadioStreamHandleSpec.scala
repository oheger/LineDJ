/*
 * Copyright 2015-2024 The Developers Team.
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
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, TryValues}
import org.scalatestplus.mockito.MockitoSugar

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.concurrent.Future

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

  "RadioStreamHandle" should "create an instance using a builder" in :
    val RadioStreamUri = "https://example.com/radio.m3u"
    val RadioStreamResolvedUri = "https://example.com/resolved/radio.mp3"
    val RadioStreamChunkCount = 16
    val BufferSize = 77
    val streamData = RadioStreamTestHelper.generateAudioDataWithMetadata(RadioStreamChunkCount,
      RadioStreamTestHelper.AudioChunkSize)(RadioStreamTestHelper.generateMetadata)
    val radioDataSource = Source.cycle(() => streamData.iterator)

    val streamBuilder = mock[RadioStreamBuilder]
    when(streamBuilder.buildRadioStream(any())(any(), any())).thenAnswer((invocation: InvocationOnMock) =>
      val params = invocation.getArgument[RadioStreamBuilder.RadioStreamParameters[SinkType, SinkType]](0)
      params.streamUri should be(RadioStreamUri)
      params.bufferSize should be(BufferSize)
      val (graph, killSwitch) =
        RadioStreamBuilder.createGraphForSource(radioDataSource, params, Some(RadioStreamTestHelper.AudioChunkSize))
      val result = RadioStreamBuilder.BuilderResult(
        resolvedUri = RadioStreamResolvedUri,
        graph = graph,
        killSwitch = killSwitch,
        metadataSupported = true
      )
      Future.successful(result))

    def createSinkForQueue(queue: BlockingQueue[ByteString]): Sink[ByteString, Future[Done]] =
      Sink.foreach(queue.offer)

    @tailrec def readMetadataChunks(queue: BlockingQueue[ByteString], current: Set[ByteString]): Set[ByteString] =
      if queue.isEmpty then current
      else readMetadataChunks(queue, current + queue.poll())

    @tailrec def readAudioChunks(queue: BlockingQueue[ByteString], current: ByteString): ByteString =
      if current.length >= 3 * RadioStreamChunkCount * RadioStreamTestHelper.AudioChunkSize then
        current
      else
        val chunk = queue.poll(3, TimeUnit.SECONDS)
        chunk should not be null
        readAudioChunks(queue, current ++ chunk)

    RadioStreamHandle.create(streamBuilder, RadioStreamUri, BufferSize) flatMap { handle =>
      handle.attach() flatMap { (sourceAudio, sourceMeta) =>
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
          val expectedMetadata = (1 to RadioStreamChunkCount).map(RadioStreamTestHelper.generateMetadata)
          readMetadataChunks(metadataQueue, Set.empty)
            .map(_.utf8String) should contain theSameElementsAs expectedMetadata
          val expectedAudioChunks = (0 until RadioStreamChunkCount).foldLeft(ByteString.empty) { (str, idx) =>
            val block = RadioStreamTestHelper.dataBlock(RadioStreamTestHelper.AudioChunkSize, idx)
            str ++ block
          }
          audioChunks.utf8String should include(expectedAudioChunks.utf8String)
        }
      }
    }

  it should "support to detach from the radio stream sinks" in :
    val controlAudio = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val controlMeta = typedTestKit.createTestProbe[AttachableSink.AttachableSinkControlCommand[ByteString]]()
    val handle = RadioStreamHandle(
      controlAudio.ref,
      controlMeta.ref,
      mock[RadioStreamBuilder.BuilderResult[SinkType, SinkType]]
    )

    handle.detach()

    val ctrlAudioMsg = controlAudio.expectMessageType[AttachableSink.AttachableSinkControlCommand[ByteString]]
    ctrlAudioMsg should be(AttachableSink.DetachConsumer())
    val ctrlMetaMsg = controlMeta.expectMessageType[AttachableSink.AttachableSinkControlCommand[ByteString]]
    ctrlMetaMsg should be(AttachableSink.DetachConsumer())
