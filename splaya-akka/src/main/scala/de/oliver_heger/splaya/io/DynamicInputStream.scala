package de.oliver_heger.splaya.io

import java.io.InputStream

import de.oliver_heger.splaya.io.FileReaderActor.ReadResult

/**
 * Companion object of ''DynamicInputStream''.
 */
object DynamicInputStream {
  /**
   * Constant for the default capacity of a dynamic input stream. When
   * creating a new stream, this capacity is used per default. When
   * more chunks are added, the capacity grows dynamically.
   */
  val DefaultCapacity = 64
}

/**
 * A specialized ''InputStream'' class whose content can be defined dynamically
 * with chunks added to the stream.
 *
 * This stream class acts as an adapter between reactive read operations and
 * components requiring an input stream. It implements read operations on a
 * bunch of ''ReadResult'' objects that can be added at any time. It never
 * blocks.
 *
 * When reading data from this stream the passed in byte array is filled with
 * data available. If there is no data, the number of bytes read is set to 0.
 * Note that the no-argument ''read()'' method cannot be implemented in a
 * non-blocking way if there is no data available. Therefore, in this case,
 * the stream returns -1 indicating its end. To prevent this, users have
 * to ensure that the stream is sufficiently filled before data is read.
 *
 * @param initialCapacity the initial capacity of this stream; this is the
 *                        number of chunks that can be added; it grows
 *                        dynamically if necessary
 */
class DynamicInputStream(initialCapacity: Int = DynamicInputStream.DefaultCapacity) extends
InputStream {
  /**
   * An array with the chunks that have been appended to this stream. This
   * array will be used as a circular buffer when adding new chunks.
   */
  private var chunks = new Array[ReadResult](initialCapacity)

  /** The index of the current chunk to read from. */
  private var currentChunk = 0

  /** The index in the chunks array where to add the next chunk. */
  private var appendChunk = 0

  /** The current read position. */
  private var currentPosition = 0

  /** The number of bytes currently available. */
  private var bytesAvailable = 0

  /** A flag whether this stream has been completed. */
  private var contentCompleted = false

  /**
   * Appends the content stored in the given ''ReadResult'' object to this
   * stream.
   * @param data the result object to be added
   * @return a reference to this stream
   * @throws IllegalStateException if the stream is already complete
   */
  def append(data: ReadResult): DynamicInputStream = {
    if (completed) {
      throw new IllegalStateException("Cannot add data to a completed stream!")
    }

    ensureCapacity()
    chunks(appendChunk) = data
    appendChunk = increaseChunkIndex(appendChunk)
    bytesAvailable += data.length
    this
  }

  /**
   * Marks this stream as complete. This means that the whole content has been
   * added. It is now possible to read the stream to its end.
   * @return a reference to this stream
   */
  def complete(): DynamicInputStream = {
    contentCompleted = true
    this
  }

  /**
   * Returns a flag whether the content of this stream has already been
   * completed. It is then no more possible to append more data.
   * @return a flag whether this stream has been completed
   */
  def completed: Boolean = contentCompleted

  /**
   * Returns the current capacity of this stream. This is the number of chunks
   * that can be stored. The capacity is increased dynamically if necessary
   * when new chunks of data are appended.
   * @return the current capacity of this stream
   */
  def capacity: Int = chunks.length

  /**
   * @inheritdoc This implementation returns the number of bytes which has been
   *             added to this stream and not read so far.
   */
  override def available(): Int = bytesAvailable

  /**
   * @inheritdoc This implementation is limited in functionality and efficiency.
   *             For instance, it cannot handle the complete flag correctly.
   *             It is preferable to use one of the ''read()'' methods
   *             operating on an array.
   */
  override def read(): Int = {
    if (bytesAvailable > 0) {
      val result = chunks(0).data(currentPosition)
      currentPosition += 1
      bytesAvailable -= 1
      result
    } else -1
  }

  override def read(b: Array[Byte]): Int = read(b, 0, b.length)

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val readLength = math.min(len, bytesAvailable)
    if (readLength == 0 && completed) -1
    else {
      readFromChunks(b, off, readLength)

      bytesAvailable -= readLength
      readLength
    }
  }

  /**
   * Reads data from the chunks stored in this stream into the provided buffer.
   * @param b the buffer
   * @param off the offset into this buffer
   * @param readLength the number of bytes to be read
   */
  private def readFromChunks(b: Array[Byte], off: Int, readLength: Int): Unit = {
    var bytesToRead = readLength
    var arrayOffset = off

    while (bytesToRead > 0) {
      val chunkReadLength = math.min(chunks(currentChunk).length - currentPosition, bytesToRead)
      if (chunkReadLength > 0) {
        System.arraycopy(chunks(currentChunk).data, currentPosition, b, arrayOffset,
          chunkReadLength)
        currentPosition += chunkReadLength
        bytesToRead -= chunkReadLength
        arrayOffset += chunkReadLength
      } else {
        currentChunk = increaseChunkIndex(currentChunk)
        currentPosition = 0
      }
    }
  }

  /**
   * Ensures that the stream has sufficient capacity to add another chunk.
   * If necessary, the internal array is enlarged. (Every time the capacity is
   * increased, the array's size is doubled.)
   */
  private def ensureCapacity(): Unit = {
    if (appendChunk == currentChunk && available() > 0) {
      val newChunks = copyChunks()
      currentChunk = 0
      appendChunk = chunks.length
      chunks = newChunks
    }
  }

  /**
   * Creates a new array with chunks and copies the content of the old array
   * into it.
   * @return the new array with chunks
   */
  private def copyChunks(): Array[ReadResult] = {
    val newChunks = new Array[ReadResult](chunks.length * 2)
    var orgIndex = currentChunk
    for (i <- 0 until chunks.length) {
      newChunks(i) = chunks(orgIndex)
      orgIndex = increaseChunkIndex(orgIndex)
    }
    newChunks
  }

  /**
   * Increases an index in the chunks array. If the maximum capacity is
   * reached, the index starts again with 0.
   * @param index the current index value
   * @return the increased index value
   */
  private def increaseChunkIndex(index: Int): Int =
    if (index < capacity - 1) index + 1
    else 0
}
