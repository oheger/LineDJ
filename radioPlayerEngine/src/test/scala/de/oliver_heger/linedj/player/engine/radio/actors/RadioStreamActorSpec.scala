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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.IOResult
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.actors.LocalBufferActor.{BufferDataComplete, BufferDataResult}
import de.oliver_heger.linedj.player.engine.actors.PlaybackActor
import de.oliver_heger.linedj.player.engine.{AudioSource, PlayerConfig}
import org.mockito.ArgumentMatchers.{any, eq => argEq}
import org.mockito.Mockito.{timeout, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.io.{ByteArrayInputStream, IOException, InputStream}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

object RadioStreamActorSpec {
  /** URI pointing to an audio stream. */
  private val AudioStreamUri = "music.mp3"

  /** URI pointing to a m3u file. */
  private val PlaylistStreamUri = "playlist.m3u"

  /** Constant for the size of the managed buffer. */
  private val BufferSize = 16384

  /** Constant for the read chunk size. */
  private val ChunkSize = 4096

  /** Test configuration to be used by tests. */
  private val Config = createConfig()

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
    private val data = new mutable.StringBuilder

    override def read(): Int = {
      throw new UnsupportedOperationException("Unexpected method call!")
    }

    /**
      * @inheritdoc This implementation generates a chunk of data and copies it
      *             into the provided buffer.
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
    * A stream class that records all read operations executed. The data about
    * read operations is stored in a queue which can be queried by clients.
    */
  private class MonitoringStream extends TestDataGeneratorStream {
    /** The queue that stores information about read operations. */
    val readQueue = new LinkedBlockingQueue[ReadOperation]

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

  /**
    * A test stream class that throws an exception after some chunks of data
    * have been read. This is used to test error handling for streams.
    */
  private class FailingStream extends TestDataGeneratorStream {
    /** A counter for the number of generated bytes. */
    private val bytesCount = new AtomicInteger

    /**
      * @inheritdoc This implementation throws an exception after a certain
      *             amount of data has been generated.
      */
    override def read(b: Array[Byte]): Int = {
      bytesCount.addAndGet(b.length)
      if (bytesCount.get() > 3 * ChunkSize) {
        throw new IOException("Test exception when reading stream.")
      }

      super.read(b)
    }
  }

  /**
    * Creates test configuration.
    *
    * @return the configuration
    */
  private def createConfig(): PlayerConfig =
    PlayerConfig(mediaManagerActor = null, actorCreator = (_, _) => null,
      bufferChunkSize = ChunkSize, inMemoryBufferSize = BufferSize)

  /**
    * Generates a sequence of reference test data in the given range.
    *
    * @param size       the size of the sequence
    * @param skipChunks the number of chunks to skip
    * @return the array with the given range of test data
    */
  private def refData(size: Int, skipChunks: Int = 0): Array[Byte] = {
    val refStream = new TestDataGeneratorStream

    if (skipChunks > 0) {
      val skipBuf = new Array[Byte](ChunkSize)
      (1 to skipChunks) foreach (_ => refStream read skipBuf)
    }

    val refBuf = new Array[Byte](size)
    refStream read refBuf
    refBuf
  }
}

/**
  * Test class for [[RadioStreamActor]].
  */
class RadioStreamActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("RadioStreamActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import RadioStreamActorSpec._

  /**
    * Creates a mock stream reference that returns a ''Source'' backed by the
    * given stream.
    *
    * @param uri    the URI to be reported by the reference
    * @param stream the stream
    * @return the mock stream reference
    */
  private def createStreamRef(uri: String = AudioStreamUri,
                              stream: InputStream = new TestDataGeneratorStream): StreamReference = {
    val ref = mock[StreamReference]
    when(ref.uri).thenReturn(uri)
    when(ref.createSource(any())(any())).thenAnswer((invocation: InvocationOnMock) => {
      val source = createSourceFromStream(stream, invocation.getArgument(0))
      Future.successful(source)
    })

    ref
  }

  /**
    * Creates a source for a test stream.
    *
    * @param stream   the underlying stream
    * @param chunkSze the chunk size to read from the stream
    * @return the source for this stream
    */
  private def createSourceFromStream(stream: InputStream = new TestDataGeneratorStream,
                                     chunkSze: Int = ChunkSize): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => stream, chunkSze)

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
    val ref = createStreamRef()
    val helper = new StreamActorTestHelper

    helper.createTestActor(ref)

    helper.verifyM3uResolveOperation(ref)
  }

  it should "stop itself if resolving of the audio stream fails" in {
    val ref = createStreamRef(uri = PlaylistStreamUri)
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(ref)

    helper.setM3uResolveResult(Failure(new IllegalStateException("Failure")))
    expectTermination(actor)
  }

  it should "notify the source listener when the audio stream has been resolved" in {
    val helper = new StreamActorTestHelper
    helper.createTestActor(StreamReference(PlaylistStreamUri))
    helper.setM3uResolveResult(Success(createStreamRef()))

    helper.probeSourceListener.expectMsg(AudioSource(AudioStreamUri, Long.MaxValue, 0, 0))
  }

  it should "fill its internal buffer" in {
    val stream = new MonitoringStream
    val ref = createStreamRef(stream = stream)
    val helper = new StreamActorTestHelper

    helper.createTestActor(ref)
    stream.expectReadsUntil(BufferSize)
  }

  it should "stop reading when the internal buffer is full" in {
    val stream = new MonitoringStream
    val ref = createStreamRef(stream = stream)
    val helper = new StreamActorTestHelper

    helper.createTestActor(ref)
    stream.expectReadsUntil(BufferSize + 3 * ChunkSize) // Configured buffer size + some internal buffers

    stream.expectNoRead()
  }

  it should "allow reading data from the buffer" in {
    val stream = new MonitoringStream
    val ref = createStreamRef(stream = stream)
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(ref)
    stream.expectReadsUntil(ChunkSize)

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    actor ! PlaybackActor.GetAudioData(ChunkSize)

    val msg1 = expectMsgType[BufferDataResult]
    msg1.data.length should be(ChunkSize)
    msg1.data.toArray should be(refData(ChunkSize))
    val msg2 = expectMsgType[BufferDataResult]
    msg2.data.length should be(ChunkSize)
    msg2.data.toArray should be(refData(ChunkSize, skipChunks = 1))
  }

  it should "fill the buffer again when data has been read" in {
    val stream = new MonitoringStream
    val ref = createStreamRef(stream = stream)
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(ref)
    stream.expectReadsUntil(BufferSize)

    actor ! PlaybackActor.GetAudioData(ChunkSize)

    expectMsgType[BufferDataResult]
    stream.expectRead().requestSize should be(ChunkSize)
  }

  it should "handle reads before the stream is initialized" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(StreamReference(PlaylistStreamUri))

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    helper.setM3uResolveResult(Success(createStreamRef()))

    val msg = expectMsgType[BufferDataResult]
    msg.data.toArray should be(refData(ChunkSize))
  }

  it should "handle a close request by closing the stream" in {
    val stream = new MonitoringStream
    val ref = createStreamRef(stream = stream)
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(ref)
    stream.expectRead()

    actor ! CloseRequest
    expectMsg(CloseAck(actor))
    awaitCond(stream.closed.get() == 1)
  }

  it should "answer data requests in closing state with an EoF message" in {
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(createStreamRef())
    actor ! CloseRequest
    expectMsg(CloseAck(actor))

    actor ! PlaybackActor.GetAudioData(ChunkSize)
    expectMsg(BufferDataComplete)
  }

  it should "handle an AudioSourceResolved message in closing state" in {
    val ref = mock[StreamReference]
    val promiseSource = Promise[Source[ByteString, Future[IOResult]]]()
    val sourceRequested = new AtomicBoolean
    when(ref.createSource(any())(any())).thenAnswer((_: InvocationOnMock) => {
      sourceRequested set true
      promiseSource.future
    })

    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(StreamReference(PlaylistStreamUri))

    helper.setM3uResolveResult(Success(ref))
    awaitCond(sourceRequested.get())
    actor ! CloseRequest
    promiseSource.success(createSourceFromStream())

    expectMsg(CloseAck(actor))
  }

  it should "stop itself if the managed stream terminates unexpectedly" in {
    val ref = createStreamRef(stream = new ByteArrayInputStream(FileTestHelper.testBytes()))
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(ref)

    expectTermination(actor)
  }

  it should "stop itself if the audio stream cannot be resolved" in {
    val refNonExistingAudioStream = StreamReference("non-existing-audio-stream.mp3")
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(StreamReference(PlaylistStreamUri))

    helper.setM3uResolveResult(Success(refNonExistingAudioStream))

    expectTermination(actor)
  }

  it should "stop itself if the managed stream throws an exception" in {
    val refFailingAudioStream = createStreamRef(stream = new FailingStream)
    val helper = new StreamActorTestHelper
    val actor = helper.createTestActor(StreamReference(PlaylistStreamUri))

    helper.setM3uResolveResult(Success(refFailingAudioStream))

    expectTermination(actor)
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

    /** A mock for the object that resolves m3u URIs. */
    private val m3uReader = createM3uReader()

    def createTestActor(streamRef: StreamReference): ActorRef = {
      val props = RadioStreamActor(Config, streamRef, probeSourceListener.ref, m3uReader)
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
          //case o => fail("Unsupported argument: " + o)
        })
      reader
    }
  }
}
