/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.download

import akka.util.ByteString

import scala.collection.immutable.Queue

private object DownloadBuffer {
  /**
    * Returns an empty ''DownloadBuffer''.
    *
    * @return the empty buffer
    */
  val empty: DownloadBuffer = new DownloadBuffer(Queue.empty, 0, None)

  /**
    * Splits a byte string if necessary. If the string is larger than the
    * requested count, it has to be split in two parts.
    *
    * @param bs     the byte string to split
    * @param length the requested length
    * @return the string of the requested size and the optional remainder
    */
  private def splitBlock(bs: ByteString, length: Int): (ByteString, Option[ByteString]) =
    if (bs.length <= length) (bs, None)
    else {
      val (b1, b2) = bs.splitAt(length)
      (b1, Some(b2))
    }
}

/**
  * An internally used helper class that manages the in-memory buffer during a
  * download operation.
  *
  * When downloading a file from an HTTP archive chunks of data are added to
  * an in-memory buffer and requested from the client. Ideally, the client is
  * fast enough to process the data in the same speed as it arrives from the
  * HTTP server. If this is not the case, the single chunks have to be
  * buffered. If the buffer becomes too big, it has to be written to a
  * temporary file.
  *
  * This class offers the API to implement these use cases. It allows adding
  * blocks of bytes and removing them in FIFO order. It also exposes all
  * blocks currently stored, so that they can be written to disk.
  *
  * @param queue         stores the content of this buffer
  * @param size          the number of types contained in this buffer
  * @param optFirstBlock an optional first block; such a block is created if
  *                      a chunk is requested that is smaller than the head
  *                      chunk
  */
private class DownloadBuffer private(queue: Queue[ByteString], val size: Int,
                                     optFirstBlock: Option[ByteString]) {

  import DownloadBuffer._

  /**
    * Returns a new ''DownloadBuffer'' as a copy of this instance with the
    * specified block added to the end of the queue.
    *
    * @param chunk the chunk to be added
    * @return the updated buffer
    */
  def addChunk(chunk: ByteString): DownloadBuffer =
    new DownloadBuffer(queue :+ chunk, size + chunk.length, optFirstBlock)

  /**
    * Obtains a block of data with the given size from this buffer and
    * returns a modified buffer with the data removed. The block returned has
    * at most the specified size; it may be smaller if this fits the chunk
    * size used by the HTTP archive better. If this buffer is empty, the block
    * returned is ''None''.
    *
    * @param blockSize the size of the desired block
    * @return a tuple with the block result and the updated buffer
    */
  def fetchData(blockSize: Int): (Option[ByteString], DownloadBuffer) =
    optFirstBlock.map(bs =>
      fetchDataWithFirstBlock(blockSize, bs)) getOrElse fetchDataFromQueue(blockSize)

  /**
    * Returns an ''Iterable'' with all ''ByteString'' blocks that are
    * contained in this buffer.
    *
    * @return an ''Iterable'' with all contained blocks
    */
  def chunks: Iterable[ByteString] =
    optFirstBlock.map(_ :: queue.toList) getOrElse queue.toList

  /**
    * Obtains a block with data of the requested size if there is a first
    * block available.
    *
    * @param blockSize the desired block size
    * @param bs        the content of the first block
    * @return a tuple with the block result and the updated buffer
    */
  private def fetchDataWithFirstBlock(blockSize: Int, bs: ByteString):
  (Some[ByteString], DownloadBuffer) = {
    val (value, rest) = splitBlock(bs, blockSize)
    (Some(value), new DownloadBuffer(queue, size - value.length, rest))
  }

  /**
    * Obtains a block with data of the requested size if all data of this
    * buffer is stored in the queue.
    *
    * @param blockSize the desired block size
    * @return a tuple with the block result and the updated buffer
    */
  private def fetchDataFromQueue(blockSize: Int): (Option[ByteString], DownloadBuffer) =
    if (queue.isEmpty) (None, this)
    else {
      val (bs, q2) = queue.dequeue
      val (value, rest) = splitBlock(bs, blockSize)
      (Some(value), new DownloadBuffer(q2, size - value.length, rest))
    }
}
