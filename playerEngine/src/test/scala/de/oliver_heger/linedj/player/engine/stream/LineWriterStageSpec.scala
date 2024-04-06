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

package de.oliver_heger.linedj.player.engine.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{ActorAttributes, FlowShape, Graph}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.*
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.Inspectors.forAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues, Succeeded}
import org.scalatestplus.mockito.MockitoSugar

import javax.sound.sampled.SourceDataLine
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
  * Test class for [[LineWriterStage]].
  */
class LineWriterStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues with MockitoSugar:
  def this() = this(ActorSystem("LineWriterStageSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  /**
    * Runs a stream with a test stage from the given source.
    *
    * @param source the source defining the stream input
    * @param stage  the test stage
    * @return the accumulated (reversed) output of the stream
    */
  private def runStream(source: Source[ByteString, Any],
                        stage: Graph[FlowShape[ByteString, LineWriterStage.PlayedAudioChunk], NotUsed]):
  Future[List[LineWriterStage.PlayedAudioChunk]] =
    val sink = Sink.fold[List[LineWriterStage.PlayedAudioChunk],
      LineWriterStage.PlayedAudioChunk](List.empty) { (lst, chunk) =>
      chunk :: lst
    }
    source.via(stage).runWith(sink)

  "LineWriterStage" should "be prepared to run on a blocking dispatcher" in :
    val DispatcherName = "mySpecialDispatcherForBlockingStages"
    val line = mock[SourceDataLine]
    val stage = LineWriterStage(line, dispatcherName = DispatcherName)

    val dispatcherAttr = stage.getAttributes.get[ActorAttributes.Dispatcher]
    dispatcherAttr.value.dispatcher should be(DispatcherName)

  it should "pass all data to the line" in :
    val line = mock[SourceDataLine]
    val data = List(ByteString("chunk1"), ByteString("chunk2"), ByteString("another chunk"))
    val stage = LineWriterStage(line)

    runStream(Source(data), stage) map :
      _ =>
        data.foreach { chunk =>
          verify(line).write(chunk.toArray, 0, chunk.size)
        }
        Succeeded

  it should "produce results with correct sizes" in :
    val line = mock[SourceDataLine]
    val data = List(ByteString("foo"), ByteString("bar"), ByteString("blubb"))
    val stage = LineWriterStage(line)

    runStream(Source(data), stage) map :
      result =>
        result.map(_.size) should be(List(5, 3, 3))

  it should "produce results with playback times" in :
    val line = mock[SourceDataLine]
    when(line.write(any(), any(), any())).thenAnswer((invocation: InvocationOnMock) =>
      Thread.sleep(1)
      1)
    val data = List(ByteString("x"), ByteString("y"), ByteString("z"), ByteString("!"))
    val stage = LineWriterStage(line)

    runStream(Source(data), stage) map :
      result =>
        val durations = result.map(_.duration)
        forAll(durations) {
          _ should be >= 1.millis
        }

  it should "drain and close the line after completion of the stream" in :
    val line = mock[SourceDataLine]
    val stage = LineWriterStage(line)

    runStream(Source.single(ByteString("someAudioData")), stage) map :
      _ =>
        val inOrderVerifier = Mockito.inOrder(line)
        inOrderVerifier.verify(line).write(any(), any(), any())
        inOrderVerifier.verify(line).drain()
        inOrderVerifier.verify(line).close()
        Succeeded

  it should "drain and close the line after an error happened" in :
    val line = mock[SourceDataLine]
    val stage = LineWriterStage(line)

    val futStream = recoverToSucceededIf[IllegalStateException]:
      runStream(Source.failed(new IllegalStateException("Test exception")), stage)
    futStream map :
      _ =>
        val inOrderVerifier = Mockito.inOrder(line)
        inOrderVerifier.verify(line).drain()
        inOrderVerifier.verify(line).close()
        Succeeded

  it should "not close the line if this is disabled" in :
    val line = mock[SourceDataLine]
    val stage = LineWriterStage(line, closeLine = false)

    runStream(Source.single(ByteString("someAudioData")), stage) map :
      _ =>
        verify(line).drain()
        verify(line, never()).close()
        Succeeded
    