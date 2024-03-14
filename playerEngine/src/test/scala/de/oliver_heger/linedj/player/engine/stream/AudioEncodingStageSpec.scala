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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}

import java.io.InputStream
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.Random

object AudioEncodingStageSpec:
  /** The chunk size for data coming from the source. */
  private val InputChunkSize = 64

  /** The limit before the stream factory can be invoked. */
  private val StreamFactoryLimit = 129

  /** The limit of the encoder stream. */
  private val EncoderStreamLimit = 100

  /** The chunk size for reading from the encoder stream. */
  private val EncoderStreamChunkSize = 70

  /** The byte used for XOR encoding. */
  private val EncodeByte: Byte = 42

  /**
    * A data class storing information about a read operation. This is used by
    * [[DummyEncoderStream]] to provide information about the reads done by
    * this stream.
    *
    * @param available  the number of bytes available in the source stream
    * @param bufferSize the size of the read buffer
    */
  private case class ReadData(available: Int, bufferSize: Int)

  /**
    * Implementation of a stream that performs a dummy encoding based on the
    * ''encodeBytes()'' function. This implementation expects that only the
    * ''read()'' function expecting a byte array is called.
    *
    * @param source    the source stream to read data from
    * @param readQueue a queue to propagate read information
    */
  private class DummyEncoderStream(source: InputStream, readQueue: BlockingQueue[ReadData]) extends InputStream:
    override def read(): Int =
      throw UnsupportedOperationException("Unexpected invocation.")

    override def read(b: Array[Byte]): Int =
      val readData = ReadData(available = source.available(), bufferSize = b.length)
      readQueue.offer(readData)

      val buffer = Array.ofDim[Byte](b.length)
      val size = source.read(buffer)

      if size < 0 then -1
      else
        val data = if size < buffer.length then buffer.take(size) else buffer
        val encodedBytes = encodeBytes(data)
        System.arraycopy(encodedBytes, 0, b, 0, encodedBytes.length)
        encodedBytes.length

    override def read(b: Array[Byte], off: Int, len: Int): Int =
      throw UnsupportedOperationException("Unexpected invocation.")
  end DummyEncoderStream

  /**
    * Simulates an encoding of the given input data.
    *
    * @param data the input data to be encoded
    * @return the resulting array with "encoded" bytes
    */
  private def encodeBytes(data: Array[Byte]): Array[Byte] =
    val buf = ArrayBuffer.empty[Byte]
    data.filterNot(_ == 0).foreach { b =>
      buf += (b ^ EncodeByte).toByte
    }
    buf.toArray

  /**
    * Returns a configuration for the test stage.
    *
    * @param readDataQueue the queue where to store read information
    * @return the configuration for the test stage
    */
  private def createStageConfig(readDataQueue: BlockingQueue[ReadData]): AudioEncodingStage.AudioEncodingStageConfig =
    val streamFactory: AudioEncodingStage.EncoderStreamFactory = input => {
      readDataQueue.offer(ReadData(input.available(), -1))
      new DummyEncoderStream(input, readDataQueue)
    }

    AudioEncodingStage.AudioEncodingStageConfig(
      streamFactory = streamFactory,
      streamFactoryLimit = StreamFactoryLimit,
      encoderStreamLimit = EncoderStreamLimit,
      encoderStreamChunkSize = EncoderStreamChunkSize
    )

  /**
    * Returns a [[Sink]] that combines all byte strings received through the
    * stream.
    *
    * @return the [[Sink]] performing a ''fold'' operation
    */
  private def foldSink(): Sink[ByteString, Future[ByteString]] =
    Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
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
    * Runs a stream on the given data with an [[AudioEncodingStage]] and checks
    * whether the correct encoded result is produced.
    *
    * @param data          the source data to pass through the stream
    * @param readDataQueue the queue to propagate read information
    * @return a ''Future'' with the test assertion
    */
  private def runEncoding(data: ByteString,
                          readDataQueue: BlockingQueue[ReadData] = new LinkedBlockingQueue[ReadData]):
  Future[Assertion] =
    val source = Source(data.grouped(InputChunkSize).toList)
    val sink = foldSink()
    val stage = new AudioEncodingStage(createStageConfig(readDataQueue))
    source.via(stage).runWith(sink) map { streamResult =>
      val expectedResult = ByteString(encodeBytes(data.toArray))
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
    val data = generateData(20240310205425L, 768)
    data(17) = 0 // Make sure that filtering has to be applied.

    runEncoding(ByteString(data))

  it should "produce the correct encoded result for an input source smaller than the factory limit" in :
    val data = generateData(20240310220348L, StreamFactoryLimit - 1)

    runEncoding(ByteString(data))

  it should "use the correct chunk size when reading from the encoded stream" in :
    val data = generateData(20240311213005L, 512)
    val readQueue = new LinkedBlockingQueue[ReadData]

    runEncoding(ByteString(data), readQueue) map { _ =>
      readQueue.poll() // The first item is from the factory.
      var reads = List.empty[ReadData]
      while !readQueue.isEmpty do
        reads = readQueue.poll() :: reads
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
    val data = generateData(20240311220000L, 2048)
    val readQueue = new LinkedBlockingQueue[ReadData]

    runEncoding(ByteString(data), readQueue) map { _ =>
      var reads = List.empty[ReadData]
      while !readQueue.isEmpty do
        reads = readQueue.poll() :: reads
      reads.reverse.take(25)
        .forall(_.available >= EncoderStreamLimit) shouldBe true
    }

  it should "wipe out the data stream if an empty read occurs" in :
    val chunk1 = Array.ofDim[Byte](StreamFactoryLimit)
    chunk1(StreamFactoryLimit - 1) = 'a'
    chunk1(StreamFactoryLimit - 2) = 'b'
    chunk1(StreamFactoryLimit - 3) = 'c'
    val (chunk2, chunk3) = generateData(20240312220501L, 2 * StreamFactoryLimit).splitAt(StreamFactoryLimit)
    val source = Source(List(chunk1, chunk2, chunk3)).map(ByteString.apply)
    val sink = foldSink()
    val stage = new AudioEncodingStage(createStageConfig(new LinkedBlockingQueue))

    source.via(stage).runWith(sink) map { streamResult =>
      val expectedResult = ByteString(encodeBytes(chunk2)) ++ ByteString(encodeBytes(chunk3))
      streamResult should be(expectedResult)
    }

  it should "handle an empty read after upstream has finished" in :
    val chunk = Array.ofDim[Byte](2 * StreamFactoryLimit)
    chunk(0) = 'x'
    chunk(2 * EncoderStreamChunkSize + 1) = 'y'
    chunk(3 * EncoderStreamChunkSize + 5) = 'z'
    val source = Source(List(ByteString(chunk)))
    val sink = foldSink()
    val stage = new AudioEncodingStage(createStageConfig(new LinkedBlockingQueue))
    
    source.via(stage).runWith(sink) map { streamResult =>
      val expectedResult = ByteString(encodeBytes(chunk.take(EncoderStreamChunkSize)))
      streamResult should be(expectedResult)
    }
    