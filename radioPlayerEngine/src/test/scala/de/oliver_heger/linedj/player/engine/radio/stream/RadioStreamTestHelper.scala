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

import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.server.Directives.complete
import org.apache.pekko.http.scaladsl.server.StandardRoute
import org.apache.pekko.stream.IOResult
import org.apache.pekko.stream.scaladsl.{Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString

import java.io.{IOException, InputStream}
import java.net.ServerSocket
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Using

/**
  * A test helper module providing several stream classes that are useful for
  * testing radio stream processing.
  */
object RadioStreamTestHelper:
  /** Constant for the read chunk size. */
  final val ChunkSize = 4096

  /** The chunk size of audio blocks. */
  final val AudioChunkSize = 256

  /** Constant for the host of the test server. */
  private val Host = "localhost"

  /** A string used to generate blocks of metadata. */
  private val MetaDataBlockContent = "Metadata block "

  /**
    * A case class that represents a read operation on the wrapped input stream.
    * The data does not matter, only the size is relevant.
    *
    * @param requestSize the size of the read request
    * @param resultSize  the number of bytes returned by the operation
    * @param at          the time when the operation was executed
    */
  case class ReadOperation(requestSize: Int, resultSize: Int, at: Long)

  /**
    * A test stream class that can generate an infinite sequence of test data
    * by concatenating a configurable string again and again.
    *
    * @param testData the string used to generate the test data
    */
  class TestDataGeneratorStream(testData: String = FileTestHelper.TestData) extends InputStream:
    private val data = new mutable.StringBuilder

    override def read(): Int =
      throw new UnsupportedOperationException("Unexpected method call!")

    /**
      * @inheritdoc This implementation generates a chunk of data and copies it
      *             into the provided buffer.
      */
    override def read(b: Array[Byte]): Int =
      @tailrec def fillBuffer(len: Int): Unit =
        if data.length < len then
          data append testData
          fillBuffer(len)

      val len = b.length
      fillBuffer(len)
      val bytes = data.substring(0, len).getBytes("utf-8")
      data.delete(0, len)
      System.arraycopy(bytes, 0, b, 0, len)
      len

  /**
    * A stream class that records all read operations executed. The data about
    * read operations is stored in a queue which can be queried by clients.
    *
    * @param testData the string used to generate the test data
    */
  class MonitoringStream(testData: String = FileTestHelper.TestData) extends TestDataGeneratorStream(testData):
    /** The queue that stores information about read operations. */
    val readQueue = new LinkedBlockingQueue[ReadOperation]

    /** A flag whether this stream has been closed. */
    val closed = new AtomicInteger

    /** Stores the number of bytes that have been read from this stream. */
    val bytesCount = new AtomicLong

    /**
      * Expects a read operation on this stream and returns the corresponding
      * operation object. This method fails if no read was performed in a
      * certain timeout.
      *
      * @return the ''ReadOperation''
      */
    def expectRead(): ReadOperation =
      val op = readQueue.poll(3, TimeUnit.SECONDS)
      if op == null then throw new AssertionError("No read operation within timeout!")
      op

    /**
      * Expects that no read operation is performed within a certain timeout.
      */
    def expectNoRead(): Unit =
      val op = readQueue.poll(500, TimeUnit.MILLISECONDS)
      if op != null then
        throw new AssertionError("Expected no read operation, but was " + op)

    /**
      * Expects a series of read operations until the specified size is
      * reached.
      *
      * @param size the expected size
      * @return the reverse sequence of read operations
      */
    def expectReadsUntil(size: Int): List[ReadOperation] =
      @tailrec def go(currentSize: Int, reads: List[ReadOperation]): List[ReadOperation] =
        if currentSize >= size then reads
        else
          val op = expectRead()
          go(currentSize + op.resultSize, op :: reads)

      go(0, Nil)

    /**
      * @inheritdoc This implementation adds corresponding ''ReadOperation''
      *             instances to the queue.
      */
    override def read(b: Array[Byte]): Int =
      val result = super.read(b)
      bytesCount.addAndGet(result)
      readQueue add ReadOperation(b.length, result, System.nanoTime())
      result

    /**
      * @inheritdoc Records this close operation.
      */
    override def close(): Unit =
      closed.incrementAndGet()
      super.close()

  /**
    * A test stream class that throws an exception after some chunks of data
    * have been read. This is used to test error handling for streams.
    */
  class FailingStream extends TestDataGeneratorStream:
    /** A counter for the number of generated bytes. */
    private val bytesCount = new AtomicInteger

    /**
      * @inheritdoc This implementation throws an exception after a certain
      *             amount of data has been generated.
      */
    override def read(b: Array[Byte]): Int =
      bytesCount.addAndGet(b.length)
      if bytesCount.get() > 3 * ChunkSize then
        throw new IOException("Test exception when reading stream.")

      super.read(b)

  /**
    * A trait that can be mixed in by a test class to have support for a simple
    * stub server. The server is available during test execution time. It can
    * be configured with a routing function to handle specific requests.
    */
  trait StubServerSupport:
    this: AsyncTestHelper =>

    /**
      * Runs a test that needs a mock server. A server is started at a random
      * port and configured with the given routing function. Then this function
      * executes the test block, passing in the root URL to the server.
      *
      * @param route    the routing function
      * @param block    the test block to execute
      * @param provider the provider for the actor system
      */
    def runWithServer(route: HttpRequest => Future[HttpResponse])
                     (block: String => Unit)
                     (implicit provider: ClassicActorSystemProvider): Unit =
      val port = findFreePort()
      val binding = futureResult(Http().newServerAt(Host, port).bind(route))

      try
        block(s"http://$Host:$port")
      finally
        futureResult(binding.unbind())

  /**
    * Generates a sequence of reference test data in the given range.
    *
    * @param size       the size of the sequence
    * @param skipChunks the number of chunks to skip
    * @param chunkSize  the size of a single chunk
    * @return the array with the given range of test data
    */
  def refData(size: Int, skipChunks: Int = 0, chunkSize: Int = ChunkSize): Array[Byte] =
    val refStream = new TestDataGeneratorStream

    if skipChunks > 0 then
      val skipBuf = new Array[Byte](chunkSize)
      (1 to skipChunks) foreach (_ => refStream read skipBuf)

    val refBuf = new Array[Byte](size)
    refStream read refBuf
    refBuf

  /**
    * Creates a source for a test stream.
    *
    * @param stream   the underlying stream
    * @param chunkSze the chunk size to read from the stream
    * @return the source for this stream
    */
  def createSourceFromStream(stream: InputStream = new TestDataGeneratorStream,
                             chunkSze: Int = ChunkSize): Source[ByteString, Future[IOResult]] =
    StreamConverters.fromInputStream(() => stream, chunkSze)

  /**
    * Generates a byte string with test data in the given range.
    *
    * @param blockSize the size of the block
    * @param index     the start index of this block
    * @return the byte string with test data in this range
    */
  def dataBlock(blockSize: Int, index: Int = 0): ByteString =
    ByteString(refData(blockSize, skipChunks = index, chunkSize = blockSize))

  /**
    * Generates a block with metadata by putting a length byte before the given
    * content. It is not checked whether the length fits to the content.
    *
    * @param lengthByte the byte with the encoded block length
    * @param content    the content of the metadata
    * @return the resulting block with metadata
    */
  def metadataBlock(lengthByte: Byte, content: ByteString): ByteString =
    val lengthField = ByteString(Array[Byte](lengthByte))
    lengthField ++ content

  /**
    * Generates a block with metadata for the given content by computing the
    * encoded size and putting it before the content.
    *
    * @param content the content of the metadata
    * @return the resulting block with metadata
    */
  def metadataBlock(content: ByteString): ByteString =
    metadataBlock((content.size / 16).toByte, content)

  /**
    * Generates a metadata string based on the given index. The resulting
    * string has a length that is a multitude of 16.
    *
    * @param index the index
    * @return the metadata string with this index
    */
  def generateMetadata(index: Int): String =
    val content = MetaDataBlockContent + index
    content + " " * ((16 - content.length % 16) % 16)

  /**
    * Generates a number of chunks for audio data with inlined metadata. This
    * simulates the content of a radio stream.
    *
    * @param chunkCount      the number of chunks to generate
    * @param streamChunkSize the size of the ''ByteString''s in the stream
    * @param metaGen         function to generate metadata by a block index
    * @return a list with the generated chunks
    */
  def generateAudioDataWithMetadata(chunkCount: Int, streamChunkSize: Int)
                                   (metaGen: Int => String): List[ByteString] =
    val audioData = (0 until chunkCount).map(RadioStreamTestHelper.dataBlock(AudioChunkSize, _))
    val metadata = (1 to chunkCount).map { idx =>
      RadioStreamTestHelper.metadataBlock(ByteString(metaGen(idx)))
    }

    audioData.zip(metadata)
      .foldLeft(ByteString.empty) { (d, t) => d ++ t._1 ++ t._2 }
      .grouped(streamChunkSize)
      .toList

  /**
    * Generates the ''Source'' of a radio stream consisting of a configurable
    * number of audio data chunks with inlined metadata chunks.
    *
    * @param chunkCount      the number of chunks to generate
    * @param streamChunkSize the size of the ''ByteString''s in the stream
    * @param metaGen         function to generate metadata by a block index
    * @return the ''Source'' of this simulated radio stream
    */
  def generateRadioStreamSource(chunkCount: Int,
                                streamChunkSize: Int = 100,
                                metaGen: Int => String = generateMetadata): Source[ByteString, NotUsed] =
    Source(generateAudioDataWithMetadata(chunkCount, streamChunkSize)(metaGen))

  /**
    * Returns a sink that aggregates all input into a single ''ByteString''.
    *
    * @return the aggregating sink
    */
  def aggregateSink(): Sink[ByteString, Future[ByteString]] = Sink.fold(ByteString.empty)(_ ++ _)

  /**
    * Completes the current request with a response containing the test data
    * text.
    *
    * @return the route to complete the request with test data
    */
  def completeTestData(): StandardRoute =
    complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, FileTestHelper.TestData))

  /**
    * Determines a free port number that can be used by the test server.
    *
    * @return the free port number
    */
  private def findFreePort(): Int =
    Using(new ServerSocket(0)) {
      _.getLocalPort
    }.get
