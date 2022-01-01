/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorRef, ActorSystem, OneForOneStrategy, Props, Terminated}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.{FileTestHelper, SupervisionTestActor}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.annotation.tailrec

object StreamBufferActorSpec {
  /** Constant for the size of the managed buffer. */
  private val BufferSize = 16384

  /** Constant for the read chunk size. */
  private val ChunkSize = 4096

  /** Test configuration to be used by tests. */
  private val Config = createConfig()

  /**
    * Creates test configuration.
    *
    * @return the configuration
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(mediaManagerActor = null, actorCreator = (_, _) => null,
      bufferChunkSize = ChunkSize, inMemoryBufferSize = BufferSize)

  /**
    * Generates a sequence of reference test data.
    *
    * @param size the size of the sequence
    * @return the array with the test data
    */
  private def refData(size: Int): Array[Byte] = {
    val refStream = new TestDataGeneratorStream
    val refBuf = new Array[Byte](size)
    refStream read refBuf
    refBuf
  }
}

/**
  * Test class for ''StreamBufferActor''.
  */
class StreamBufferActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  ImplicitSender with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {

  import StreamBufferActorSpec._

  def this() = this(ActorSystem("StreamBufferActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  /**
    * Creates a test actor instance which wraps the specified stream.
    *
    * @param stream the stream
    * @param conf   the configuration for the player
    * @return the test actor instance
    */
  private def createTestActor(stream: TestBufferedStream, conf: PlayerConfig = Config): ActorRef =
    system.actorOf(createTestActorProps(stream, conf))

  /**
    * Creates properties for a test actor instance.
    *
    * @param stream the stream to be wrapped
    * @param conf   the configuration for the player
    * @return properties for the test actor instance
    */
  private def createTestActorProps(stream: InputStream, conf: PlayerConfig = Config): Props =
    Props(classOf[StreamBufferActor], conf, createStreamRef(stream))

  /**
    * Creates a mock stream reference that always returns the provided
    * stream.
    *
    * @param stream the stream
    * @return the mock stream reference
    */
  private def createStreamRef(stream: InputStream): StreamReference = {
    val ref = mock[StreamReference]
    when(ref.openStream()).thenReturn(stream)
    ref
  }

  "A StreamBufferActor" should "fill its internal buffer" in {
    val stream = new MonitoringStream

    createTestActor(stream)
    stream.expectReadsUntil(BufferSize)
  }

  it should "stop reading when the internal buffer is full" in {
    val stream = new MonitoringStream
    createTestActor(stream)

    stream.expectReadsUntil(BufferSize)
    stream.expectNoRead()
  }

  it should "read in the configured chunk size" in {
    val stream = new MonitoringStream
    createTestActor(stream)

    val reads = stream.expectReadsUntil(BufferSize)
    // sum over read results
    val bytesRead = reads.foldRight((0, List.empty[Int])) {
      (o, t) => (t._1 + o.resultSize, t._1 :: t._2)
    }._2
    reads.zip(bytesRead) forall { t =>
      (t._1.requestSize <= ChunkSize) && (t._1.requestSize == ChunkSize || t._1.requestSize + t
        ._2 == BufferSize)
    } shouldBe true
  }

  it should "allow reading data from the buffer" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    stream.expectReadsUntil(ChunkSize)

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    val msg = expectMsgType[BufferDataResult]
    msg.data.length should be(ChunkSize)
    msg.data.toArray should be(refData(ChunkSize))
  }

  it should "not read more data than is currently contained in the buffer" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    stream.expectReadsUntil(BufferSize)

    actor ! PlaybackActor.GetAudioData(BufferSize + 10)
    val msg = expectMsgType[BufferDataResult]
    msg.data.length should be(BufferSize)
    msg.data.toArray should be(refData(BufferSize))
  }

  it should "handle messages during reads for filling the buffer" in {
    val Delay = 200
    val stream = new MonitoringStream {
      override def read(b: Array[Byte]): Int = {
        Thread sleep Delay
        super.read(b)
      }
    }
    val actor = createTestActor(stream)
    stream.expectRead()

    actor ! PlaybackActor.GetAudioData(32)
    expectMsgType[BufferDataResult]
    val time = System.nanoTime()

    @tailrec def findReadAfterGet(): Unit = {
      val read = stream.expectRead()
      if (read.at < time) findReadAfterGet()
    }
    findReadAfterGet()
  }

  it should "fill the buffer again when data has been read" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    stream.expectReadsUntil(BufferSize)
    val Count = 8

    actor ! PlaybackActor.GetAudioData(Count)
    expectMsgType[BufferDataResult]
    stream.expectRead().requestSize should be(Count)
  }

  it should "handle a close request by closing the stream" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    stream.expectRead()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    stream.closed.get() should be(1)
  }

  it should "read no more data after a close request" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream, Config.copy(bufferChunkSize = 4))
    stream.expectRead()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    stream.readQueue.clear()
    stream.expectNoRead()
  }

  it should "answer data requests in closing state with an EoF message" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    actor ! CloseRequest
    expectMsg(CloseAck(actor))

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    expectMsg(BufferDataComplete)
  }

  it should "throw an exception when the wrapped stream terminates" in {
    val stream = new ByteArrayInputStream(FileTestHelper.testBytes())
    val strategy = OneForOneStrategy() {
      case _: IOException => Stop
    }
    val superActor = SupervisionTestActor(system, strategy, createTestActorProps(stream))
    val actor = superActor.underlyingActor.childActor

    awaitTermination(actor)
  }

  /**
    * Waits until the specified actor is terminated.
    *
    * @param actor the actor in question
    */
  private def awaitTermination(actor: ActorRef): Unit = {
    val probe = TestProbe()
    probe watch actor
    probe.expectMsgType[Terminated].actor should be(actor)
  }

  it should "close the stream on stop if this has not been done before" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    stream.expectRead()

    system stop actor
    awaitTermination(actor)
    stream.closed.get() should be(1)
  }

  it should "close the wrapped stream only once" in {
    val stream = new MonitoringStream
    val actor = createTestActor(stream)
    stream.expectRead()
    actor ! CloseRequest
    expectMsg(CloseAck(actor))

    system stop actor
    awaitTermination(actor)
    stream.closed.get() should be(1)
  }
}

/**
  * A case class that represents a read operation on the wrapped input stream.
  * The data does not matter, only the size is relevant.
  *
  * @param requestSize the size of the read request
  * @param resultSize  the number of bytes returned by the operation
  * @param at          the time when the operation was executed
  */
private case class ReadOperation(requestSize: Int, resultSize: Int, at: Long)

/**
  * A test stream class that can generate an infinite sequence of test data.
  */
private class TestDataGeneratorStream extends InputStream {
  private val data = new StringBuilder

  override def read(): Int = {
    throw new UnsupportedOperationException("Unexpected method call!")
  }

  /**
    * @inheritdoc This implementation generates a chunk of data and copies it
    *             into the provided buffer. To be more realistic, the number
    *             of bytes copied is lower than the requested one.
    */
  override def read(b: Array[Byte]): Int = {
    @tailrec def fillBuffer(len: Int): Unit = {
      if (data.length < len) {
        data append FileTestHelper.TestData
        fillBuffer(len)
      }
    }

    val len = b.length
    fillBuffer(len)
    val bytes = data.substring(0, len).getBytes("utf-8")
    data.delete(0, len)
    System.arraycopy(bytes, 0, b, 0, len)
    len
  }
}

/**
  * A test stream class that is passed to the test actor. This stream
  * generates test data like its base class, but returns smaller chunks of
  * data to make sure that the buffer size cannot be divided by the chunk
  * size..
  */
private class TestBufferedStream extends TestDataGeneratorStream {
  /**
    * @inheritdoc This implementation generates a chunk of data and copies it
    *             into the provided buffer. To be more realistic, the number
    *             of bytes copied is lower than the requested one.
    */
  override def read(b: Array[Byte]): Int = {
    val len = math.max(b.length - 1, 1)
    if (len == b.length) super.read(b)
    else {
      val buf2 = new Array[Byte](b.length - 1)
      val len2 = super.read(buf2)
      System.arraycopy(buf2, 0, b, 0, len2)
      len2
    }
  }
}

/**
  * A stream class that records all read operations executed. The data about
  * read operations are stored in a queue which can be queried by clients.
  */
private class MonitoringStream extends TestBufferedStream {
  /** The queue that stores information about read operations. */
  val readQueue = new java.util.concurrent.LinkedBlockingQueue[ReadOperation]

  /** A flag whether this stream has been closed. */
  val closed = new AtomicInteger

  /**
    * Expects a read operation on this stream and returns the corresponding
    * operation object. This method fails if no read was performed in a
    * certain timeout.
    *
    * @return the ''ReadOperation''
    */
  def expectRead(): ReadOperation = {
    val op = readQueue.poll(3, TimeUnit.SECONDS)
    if (op == null) throw new AssertionError("No read operation within timeout!")
    op
  }

  /**
    * Expects that no read operation is performed within a certain timeout.
    */
  def expectNoRead(): Unit = {
    val op = readQueue.poll(500, TimeUnit.MILLISECONDS)
    if (op != null) {
      throw new AssertionError("Expected no read operation, but was " + op)
    }
  }

  /**
    * Expects a series of read operations until the specified size is
    * reached.
    *
    * @param size the expected size
    * @return the reverse sequence of read operations
    */
  def expectReadsUntil(size: Int): List[ReadOperation] = {
    @tailrec def go(currentSize: Int, reads: List[ReadOperation]): List[ReadOperation] = {
      if (currentSize >= size) reads
      else {
        val op = expectRead()
        go(currentSize + op.resultSize, op :: reads)
      }
    }
    go(0, Nil)
  }

  /**
    * @inheritdoc This implementation adds corresponding ''ReadOperation''
    *             instances to the queue.
    */
  override def read(b: Array[Byte]): Int = {
    val result = super.read(b)
    readQueue add ReadOperation(b.length, result, System.nanoTime())
    result
  }

  /**
    * @inheritdoc Records this close operation.
    */
  override def close(): Unit = {
    closed.incrementAndGet()
    super.close()
  }
}
