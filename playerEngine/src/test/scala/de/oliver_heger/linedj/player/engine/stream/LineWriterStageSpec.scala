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

package de.oliver_heger.linedj.player.engine.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Concat, Sink, Source}
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
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues, Succeeded, TryValues}
import org.scalatestplus.mockito.MockitoSugar

import javax.sound.sampled.{AudioFormat, AudioSystem, SourceDataLine}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Using

object LineWriterStageSpec:
  /** A format used by the test audio input streams. */
  private val Format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1, 16, 2, 2, 44100.0, false)
end LineWriterStageSpec

/**
  * Test class for [[LineWriterStage]].
  */
class LineWriterStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues with TryValues with MockitoSugar:
  def this() = this(ActorSystem("LineWriterStageSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import LineWriterStageSpec.*

  /**
    * Runs a stream with a test stage from the given source.
    *
    * @param source the source defining the stream input
    * @param stage  the test stage
    * @param format the [[AudioFormat]] of the test audio stream
    * @return the accumulated (reversed) output of the stream
    */
  private def runStream(source: Source[ByteString, Any],
                        stage: Graph[FlowShape[AudioEncodingStage.AudioData, LineWriterStage.PlayedAudioChunk],
                          NotUsed],
                        format: AudioFormat = Format):
  Future[List[LineWriterStage.PlayedAudioChunk]] =
    val headerSource: Source[AudioEncodingStage.AudioData, NotUsed] =
      Source.single(AudioEncodingStage.AudioStreamHeader(format))
    val chunkSource: Source[AudioEncodingStage.AudioData, Any] =
      source.map(AudioEncodingStage.AudioChunk.apply)
    val streamSource = Source.combine(headerSource, chunkSource)(Concat(_))
    val sink = Sink.fold[List[LineWriterStage.PlayedAudioChunk],
      LineWriterStage.PlayedAudioChunk](List.empty) { (lst, chunk) =>
      chunk :: lst
    }
    streamSource.via(stage).runWith(sink)

  /**
    * Returns a function to create a line that returns the given mock line and
    * checks for the input parameter.
    *
    * @param line   the mock line to return
    * @param format the expected audio format passed to the function
    * @return the line creator function
    */
  private def lineCreatorFunc(line: SourceDataLine,
                              format: AudioFormat = Format): Option[LineWriterStage.LineCreatorFunc] =
    val func: LineWriterStage.LineCreatorFunc = header =>
      header.format should be(format)
      line
    Some(func)

  "LineWriterStage" should "be prepared to run on a blocking dispatcher" in :
    val DispatcherName = "mySpecialDispatcherForBlockingStages"
    val line = mock[SourceDataLine]
    val stage = LineWriterStage(lineCreatorFunc(line), dispatcherName = DispatcherName)

    val dispatcherAttr = stage.getAttributes.get[ActorAttributes.Dispatcher]
    dispatcherAttr.value.dispatcher should be(DispatcherName)

  it should "provide a default line creator function" in :
    val format = Using(AudioSystem.getAudioInputStream(getClass.getResourceAsStream("/test.wav"))) { stream =>
      stream.getFormat
    }.success.value
    Using(LineWriterStage.DefaultLineCreatorFunc(AudioEncodingStage.AudioStreamHeader(format))) { _ => }.success
    Succeeded

  it should "pass all data to the line" in :
    val line = mock[SourceDataLine]
    val data = List(ByteString("chunk1"), ByteString("chunk2"), ByteString("another chunk"))
    val stage = LineWriterStage(lineCreatorFunc(line))

    runStream(Source(data), stage) map :
      _ =>
        val inorder = Mockito.inOrder(line)
        inorder.verify(line).open(Format)
        inorder.verify(line).start()
        data.foreach { chunk =>
          inorder.verify(line).write(chunk.toArray, 0, chunk.size)
        }
        Succeeded

  it should "produce results with correct sizes" in :
    val line = mock[SourceDataLine]
    val data = List(ByteString("foo"), ByteString("bar"), ByteString("blubb"))
    val stage = LineWriterStage(lineCreatorFunc(line))

    runStream(Source(data), stage) map :
      result =>
        result.map(_.size) should be(List(5, 3, 3))

  it should "produce results with correct sizes if no line is created" in :
    val data = List(ByteString("foo"), ByteString("bar"), ByteString("blubb"))
    val stage = LineWriterStage(None)

    runStream(Source(data), stage) map :
      result =>
        result.map(_.size) should be(List(5, 3, 3))

  /**
    * Checks whether results with playback times are produced in case the
    * playback duration cannot be determined from the audio format.
    *
    * @param format the format to be used
    */
  private def checkMeasuredPlaybackTimes(format: AudioFormat): Future[Assertion] =
    val line = mock[SourceDataLine]
    when(line.write(any(), any(), any())).thenAnswer((invocation: InvocationOnMock) =>
      Thread.sleep(1)
      1)
    val data = List(ByteString("x"), ByteString("y"), ByteString("z"), ByteString("!"))
    val stage = LineWriterStage(lineCreatorFunc(line, format))

    runStream(Source(data), stage, format) map :
      result =>
        val durations = result.map(_.duration)
        forAll(durations) {
          _ should be >= 1.millis
        }

  it should "produce results with playback times if no frame rate is specified in the format" in :
    val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1, 16, 2, 2, AudioSystem.NOT_SPECIFIED, false)
    checkMeasuredPlaybackTimes(format)

  it should "produce results with playback times if no frame size is specified in the format" in :
    val format = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44.1, 16, 2, AudioSystem.NOT_SPECIFIED, 44100.0, false)
    checkMeasuredPlaybackTimes(format)

  it should "produce results with exact playback times based on the audio format" in :
    val line = mock[SourceDataLine]
    val data = (1 to 5).map:
      idx =>
        val chunk = Array.fill(4096)(idx.toByte)
        ByteString(chunk)
    val stage = LineWriterStage(lineCreatorFunc(line))

    runStream(Source(data), stage) map :
      result =>
        val duration = result.map(_.duration).fold(0.nanos) { (agg, time) => agg + time }
        duration should be(232199540.nanos)

  it should "produce results with exact playback times based on the audio format if no line is created" in :
    val data = (1 to 5).map:
      idx =>
        val chunk = Array.fill(4096)(idx.toByte)
        ByteString(chunk)
    val stage = LineWriterStage(None)

    runStream(Source(data), stage) map :
      result =>
        val duration = result.map(_.duration).fold(0.nanos) { (agg, time) => agg + time }
        duration should be(232199540.nanos)

  it should "drain and close the line after completion of the stream" in :
    val line = mock[SourceDataLine]
    val stage = LineWriterStage(lineCreatorFunc(line))

    runStream(Source.single(ByteString("someAudioData")), stage) map :
      _ =>
        val inOrderVerifier = Mockito.inOrder(line)
        inOrderVerifier.verify(line).write(any(), any(), any())
        inOrderVerifier.verify(line).drain()
        inOrderVerifier.verify(line).close()
        Succeeded

  it should "drain and close the line after an error happened" in :
    val line = mock[SourceDataLine]
    val exception = new IllegalArgumentException("Test exception")
    when(line.write(any(), any(), any())).thenThrow(exception)
    val stage = LineWriterStage(lineCreatorFunc(line))

    val futStream = recoverToSucceededIf[IllegalArgumentException]:
      runStream(Source(List(ByteString("some"), ByteString("test data"))), stage)
    futStream map :
      _ =>
        val inOrderVerifier = Mockito.inOrder(line)
        inOrderVerifier.verify(line).drain()
        inOrderVerifier.verify(line).close()
        Succeeded

  it should "correctly handle an empty stream" in :
    val creatorFunc: LineWriterStage.LineCreatorFunc = _ =>
      throw new UnsupportedOperationException("Unexpected call")
    val stage = LineWriterStage(Some(creatorFunc))
    val source = Source.empty[AudioEncodingStage.AudioData]
    val sink = Sink.ignore

    source.via(stage).runWith(sink) map :
      _ => Succeeded
