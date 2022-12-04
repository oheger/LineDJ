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

import akka.stream.IOResult
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import de.oliver_heger.linedj.FileTestHelper

import java.io.{IOException, InputStream}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.Future

/**
  * A test helper module providing several stream classes that are useful for
  * testing radio stream processing.
  */
object RadioStreamTestHelper {
  /** Constant for the read chunk size. */
  final val ChunkSize = 4096

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
  class TestDataGeneratorStream(testData: String = FileTestHelper.TestData) extends InputStream {
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
          data append testData
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
    *
    * @param testData the string used to generate the test data
    */
  class MonitoringStream(testData: String = FileTestHelper.TestData) extends TestDataGeneratorStream(testData) {
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
      bytesCount.addAndGet(result)
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
  class FailingStream extends TestDataGeneratorStream {
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
    * Generates a sequence of reference test data in the given range.
    *
    * @param size       the size of the sequence
    * @param skipChunks the number of chunks to skip
    * @param chunkSize  the size of a single chunk
    * @return the array with the given range of test data
    */
  def refData(size: Int, skipChunks: Int = 0, chunkSize: Int = ChunkSize): Array[Byte] = {
    val refStream = new TestDataGeneratorStream

    if (skipChunks > 0) {
      val skipBuf = new Array[Byte](chunkSize)
      (1 to skipChunks) foreach (_ => refStream read skipBuf)
    }

    val refBuf = new Array[Byte](size)
    refStream read refBuf
    refBuf
  }

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
}
