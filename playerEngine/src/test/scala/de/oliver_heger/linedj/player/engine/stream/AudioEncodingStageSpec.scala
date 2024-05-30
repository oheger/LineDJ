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

import de.oliver_heger.linedj.player.engine
import de.oliver_heger.linedj.player.engine.AudioStreamFactory
import de.oliver_heger.linedj.player.engine.stream.AudioStreamTestHelper.{DummyEncoderStream, ReadData, encodeBytes}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.Inspectors.forAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.concurrent.Future
import scala.util.Random

object AudioEncodingStageSpec:
  /** The chunk size for data coming from the source. */
  private val InputChunkSize = 64

  /** The limit before the stream factory can be invoked. */
  private val StreamFactoryLimit = 10000

  /** The chunk size for reading from the encoder stream. */
  private val EncoderStreamChunkSize = 4097

  /** The size of the in-memory buffer to be used by the stage. */
  private val InMemoryBufferSize = 20000

  /** The header element expected to be received in test streams. */
  private val ExpectedHeader = AudioEncodingStage.AudioStreamHeader(AudioStreamTestHelper.Format)

  /**
    * A class to store the aggregated audio data that was received from a test
    * stream. It consists of the header and the collected audio data.
    *
    * @param header the header
    * @param data   the aggregated simulated audio data
    */
  private case class AggregatedAudioData(header: Option[AudioEncodingStage.AudioStreamHeader],
                                         data: ByteString)

  /**
    * Constant for an [[AggregatedAudioData]] instance that has not yet 
    * received any audio data.
    */
  private val InitialAudioData = AggregatedAudioData(None, ByteString.empty)

  /**
    * Returns a playback data object to configure the test stage.
    *
    * @param readDataQueue the queue where to store read information
    * @return the configuration for the test stage
    */
  private def createPlaybackData(readDataQueue: BlockingQueue[ReadData]): AudioStreamFactory.AudioStreamPlaybackData =
    val streamCreator: AudioStreamFactory.AudioStreamCreator = input => {
      readDataQueue.offer(ReadData(input.available(), -1))
      new DummyEncoderStream(input, readDataQueue)
    }

    AudioStreamFactory.AudioStreamPlaybackData(
      streamCreator = streamCreator,
      streamFactoryLimit = StreamFactoryLimit)

  /**
    * Polls a blocking queue with [[ReadData]] objects and returns its content
    * as a list.
    *
    * @param readQueue the queue to poll
    * @return the list of found [[ReadData]] objects
    */
  private def fetchReadData(readQueue: LinkedBlockingQueue[ReadData]): List[ReadData] =
    var reads = List.empty[ReadData]
    while !readQueue.isEmpty do
      reads = readQueue.poll() :: reads
    reads
end AudioEncodingStageSpec

/**
  * Test class for [[AudioEncodingStage]].
  */
class AudioEncodingStageSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers:
  def this() = this(ActorSystem("AudioEncodingStageSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  import AudioEncodingStageSpec.*

  /**
    * Returns a [[Sink]] that combines all elements received through the
    * stream.
    *
    * @return the [[Sink]] performing a ''fold'' operation
    */
  private def foldSink(): Sink[AudioEncodingStage.AudioData, Future[AggregatedAudioData]] =
    Sink.fold[AggregatedAudioData, AudioEncodingStage.AudioData](InitialAudioData) { (agg, chunk) =>
      chunk match
        case header: AudioEncodingStage.AudioStreamHeader =>
          agg should be(InitialAudioData)
          agg.copy(header = Some(header))
        case data: AudioEncodingStage.AudioChunk =>
          agg.header should not be empty
          agg.copy(data = agg.data ++ data.data)
    }

  /**
    * Runs a stream on the given data with an [[AudioEncodingStage]] and checks
    * whether the correct encoded result is produced.
    *
    * @param data           the source data to pass through the stream
    * @param readDataQueue  the queue to propagate read information
    * @param inputChunkSize the chunk size of the input source
    * @return a ''Future'' with the test assertion
    */
  private def runEncoding(data: ByteString,
                          readDataQueue: BlockingQueue[ReadData] = new LinkedBlockingQueue[ReadData],
                          inputChunkSize: Int = InputChunkSize):
  Future[Assertion] =
    val source = Source(data.grouped(inputChunkSize).toList)
    val sink = foldSink()
    val stage = new AudioEncodingStage(createPlaybackData(readDataQueue), InMemoryBufferSize)

    source.via(stage).runWith(sink) map { streamResult =>
      val expectedResult = AggregatedAudioData(Some(ExpectedHeader), ByteString(encodeBytes(data.toArray)))
      streamResult should be(expectedResult)
    }

  /**
    * Generates a block of random data that can be used as input for a test
    * run of the encoding stage.
    *
    * @param seed the seed for the ''Random'' object
    * @param size the size of the block to generate
    * @return the generated data
    */
  private def generateData(seed: Long, size: Int): Array[Byte] =
    val random = new Random(seed)
    random.nextBytes(size)

  "AudioEncodingStage" should "produce the correct encoded result" in :
    val data = generateData(20240310205425L, 16384)
    data(17) = 0 // Make sure that filtering has to be applied.

    runEncoding(ByteString(data))

  it should "produce the correct encoded result for an input source smaller than the factory limit" in :
    val data = generateData(20240310220348L, StreamFactoryLimit - 1)

    runEncoding(ByteString(data))

  it should "use the correct chunk size when reading from the encoded stream" in :
    val data = generateData(20240311213005L, 32767)
    val readQueue = new LinkedBlockingQueue[ReadData]

    runEncoding(ByteString(data), readQueue) map { _ =>
      readQueue.poll() // The first item is from the factory.
      val reads = fetchReadData(readQueue)
      reads.forall(_.bufferSize == EncoderStreamChunkSize) shouldBe true
    }

  it should "create the encoded stream after the configured limit is reached" in :
    val data = generateData(20240311214122L, StreamFactoryLimit + 10)
    val readQueue = new LinkedBlockingQueue[ReadData]

    runEncoding(ByteString(data), readQueue) map { _ =>
      val readData = readQueue.poll()
      readData.available should be >= StreamFactoryLimit
    }

  it should "only read from the encoded stream if the limit is reached" in :
    val data = generateData(20240311220000L, 64535)
    val readQueue = new LinkedBlockingQueue[ReadData]

    runEncoding(ByteString(data), readQueue) map { _ =>
      val reads = fetchReadData(readQueue)
      reads.reverse.take(16)
        .forall(_.available >= EncoderStreamChunkSize) shouldBe true
    }

  it should "wipe out the data stream if an empty read occurs" in :
    val chunk1 = Array.ofDim[Byte](StreamFactoryLimit)
    chunk1(StreamFactoryLimit - 1) = 'a'
    chunk1(StreamFactoryLimit - 2) = 'b'
    chunk1(StreamFactoryLimit - 3) = 'c'
    val (chunk2, chunk3) = generateData(20240312220501L, 2 * StreamFactoryLimit).splitAt(StreamFactoryLimit)
    val source = Source(List(chunk1, chunk2, chunk3)).map(ByteString.apply)
    val sink = foldSink()
    val stage = new AudioEncodingStage(createPlaybackData(new LinkedBlockingQueue))

    source.via(stage).runWith(sink) map { streamResult =>
      val expectedData = ByteString(encodeBytes(chunk2)) ++ ByteString(encodeBytes(chunk3))
      val expectedResult = AggregatedAudioData(Some(ExpectedHeader), expectedData)
      streamResult should be(expectedResult)
    }

  it should "handle an empty read after upstream has finished" in :
    val chunk = Array.ofDim[Byte](2 * StreamFactoryLimit)
    chunk(0) = 'x'
    chunk(2 * EncoderStreamChunkSize + 1) = 'y'
    chunk(3 * EncoderStreamChunkSize + 5) = 'z'
    val source = Source(List(ByteString(chunk)))
    val sink = foldSink()
    val stage = new AudioEncodingStage(createPlaybackData(new LinkedBlockingQueue))

    source.via(stage).runWith(sink) map { streamResult =>
      val expectedData = ByteString(encodeBytes(chunk.take(EncoderStreamChunkSize)))
      val expectedResult = AggregatedAudioData(Some(ExpectedHeader), expectedData)
      streamResult should be(expectedResult)
    }

  it should "not exceed the in-memory buffer size" in :
    val inputChunkSize = 8192
    val data = generateData(20240529221414L, 128000)
    val readQueue = new LinkedBlockingQueue[ReadData]

    runEncoding(ByteString(data), readQueue, inputChunkSize) map { _ =>
      val reads = fetchReadData(readQueue)
      forAll(reads) {
        _.available should be < (InMemoryBufferSize + inputChunkSize)
      }
    }
