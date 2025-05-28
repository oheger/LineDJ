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

package de.oliver_heger.linedj.extract.id3.stream

import de.oliver_heger.linedj.extract.id3.model.ID3v1Extractor
import de.oliver_heger.linedj.extract.id3.model.ID3v1Extractor.FrameSize
import de.oliver_heger.linedj.extract.id3.stream.ID3v1ExtractorSink.TailBuffer
import de.oliver_heger.linedj.extract.metadata.MetadataProvider
import org.apache.pekko.stream.{Attributes, Inlet, SinkShape}
import org.apache.pekko.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler}
import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}

object ID3v1ExtractorSink:
  /**
    * An internally used helper class to determine the last chunk of data
    * passed through the stream with a size of an ID3v1 frame. Since the chunk
    * size of the stream is unknown and can even be varying, it is not that
    * easy to do this in an efficient way. This implementation tries to avoid
    * costly operations to copy data between data chunks. It adds incoming
    * chunks to a buffer until the size of a frame is reached. When new data
    * arrives, existing chunks are deleted (oldest first) as long as the size
    * of the buffer remains bigger than the frame size. Only if the final frame
    * is actually accessed, the contained chunks are combined.
    */
  private class TailBuffer:
    /**
      * An array serving as a ring buffer to store chunks of data. The buffer
      * has the size of an ID3v1 frame to cope with the worst case that chunks
      * of size 1 are used.
      */
    private val buffer = Array.fill(FrameSize)(ByteString.empty)

    /**
      * The start index in the ring buffer. This is the position where the most
      * recent chunk is located.
      */
    private var startIndex: Int = 0

    /**
      * The end index in the ring buffer. At this position, the older chunk is
      * located which is still required to keep the size of a frame.
      */
    private var endIndex: Int = 0

    /** The total size of all chunks stored in the buffer. */
    private var size: Int = 0

    /**
      * Adds the given chunk to this buffer doing size optimization if
      * necessary.
      *
      * @param chunk the chunk to be added
      */
    def add(chunk: ByteString): Unit =
      buffer(startIndex) = chunk
      size += chunk.size
      startIndex = nextIndex(startIndex)
      strip()

    /**
      * Returns the last chunk of data with the size of an ID3v1 frame. If the
      * buffer does not contain that much data, the resulting string is
      * smaller.
      *
      * @return the tail chunk
      */
    def tail: ByteString =
      combine(ByteString.empty, endIndex).takeRight(FrameSize)

    /**
      * Strips the buffer as far as possible. Old chunks are removed as long as
      * the size of data stored in the buffer remains over the threshold of a
      * frame.
      */
    @tailrec private def strip(): Unit =
      if size > FrameSize && endIndex != startIndex then
        val currentSize = buffer(endIndex).size
        if size - currentSize >= FrameSize then
          size -= currentSize
          removeAt(endIndex)
          endIndex = nextIndex(endIndex)
          strip()

    /**
      * Constructs a combined [[ByteString]] with all the data contained in
      * this buffer.
      *
      * @param data  the aggregated data
      * @param index the current index
      * @return the combined data of this buffer
      */
    @tailrec private def combine(data: ByteString, index: Int): ByteString =
      val combined = data ++ buffer(index)
      if index == startIndex then
        combined
      else
        combine(combined, nextIndex(index))

    /**
      * Removes the element from the given index from the buffer. To make this
      * element available to GC, its reference is actually removed.
      *
      * @param index the index of the affected element
      */
    private def removeAt(index: Int): Unit =
      buffer(index) = ByteString.empty

    /**
      * Computes the next index in the ring buffer.
      *
      * @param current the current index
      * @return the next index
      */
    private def nextIndex(current: Int): Int =
      if current >= FrameSize - 1 then 0 else current + 1
end ID3v1ExtractorSink

/**
  * A sink implementation which can extract ID3v1 tags in a binary stream of
  * audio data.
  *
  * ID3v1 information is stored in the last 128 bytes of an audio file. This
  * sink therefore consumes the whole stream and remembers the final block.
  * With the help of [[ID3v1Extractor]], it tries to obtain a
  * [[MetadataProvider]] for querying the tag information. A [[Future]] with
  * this optional provider is the materialized value of this sink.
  */
class ID3v1ExtractorSink
  extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Option[MetadataProvider]]]:
  val in: Inlet[ByteString] = Inlet[ByteString]("ID3v1ProcessingSink.in")

  override def shape: SinkShape[ByteString] = SinkShape.of(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes):
  (GraphStageLogic, Future[Option[MetadataProvider]]) =
    val promiseResult = Promise[Option[MetadataProvider]]()

    val logic = new GraphStageLogic(shape):
      /** The buffer to determine the last chunk of data. */
      private val tailBuffer = new TailBuffer

      override def preStart(): Unit =
        pull(in)

      setHandler(in, new InHandler:
        override def onUpstreamFinish(): Unit =
          super.onUpstreamFinish()
          promiseResult.success(ID3v1Extractor.providerFor(tailBuffer.tail))

        override def onUpstreamFailure(ex: Throwable): Unit =
          super.onUpstreamFailure(ex)
          promiseResult.failure(ex)

        override def onPush(): Unit =
          tailBuffer.add(grab(in))
          pull(in)
      )

    (logic, promiseResult.future)
