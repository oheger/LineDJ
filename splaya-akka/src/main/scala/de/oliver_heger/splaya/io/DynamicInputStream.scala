package de.oliver_heger.splaya.io

import java.io.{IOException, InputStream}

import de.oliver_heger.splaya.io.ChannelHandler.ArraySource

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

  /**
   * Wraps the specified data array into an ''ArraySource'' object. This is
   * convenient when working with a ''DynamicInputStream'' as the ''append()''
   * method per default expects such a source. Note that for reasons of
   * efficiency the passed in array is not copied. Therefore, it must not be
   * modified afterwards.
   * @param dataArray the array to be wrapped in an ''ArraySource''
   * @param startIndex the offset of the first valid position in the array
   * @return the newly created array source object
   */
  def arraySourceFor(dataArray: Array[Byte], startIndex: Int = 0): ArraySource =
    new ArraySource {
      override val data: Array[Byte] = dataArray
      override val length: Int = dataArray.length - startIndex
      override val offset: Int = startIndex
    }

  /**
   * Constant for an index used to represent an undefined mark position.
   */
  private val UndefinedMarkIndex = -1
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
 * Implementation note: This class is not thread-safe. Append and read
 * operations have to take place in the same thread or have to be synchronized
 * properly.
 *
 * @param initialCapacity the initial capacity of this stream; this is the
 *                        number of chunks that can be added; it grows
 *                        dynamically if necessary
 */
class DynamicInputStream(initialCapacity: Int = DynamicInputStream.DefaultCapacity) extends
InputStream {
  import de.oliver_heger.splaya.io.DynamicInputStream._
  /**
   * An array with the chunks that have been appended to this stream. This
   * array will be used as a circular buffer when adding new chunks.
   */
  private var chunks = new Array[ArraySource](initialCapacity)

  /** The index of the current chunk to read from. */
  private var currentChunk = 0

  /** The index in the chunks array where to add the next chunk. */
  private var appendChunk = 0

  /** The current read position. */
  private var currentPosition = 0

  /** Stores the chunk index selected by a mark operation. */
  private var markedChunk = UndefinedMarkIndex

  /** Stores the position selected by a mark operation. */
  private var markedPosition = UndefinedMarkIndex

  /** The limit passed to the mark() method. */
  private var markReadLimit = UndefinedMarkIndex

  /** The number of bytes read since a mark operation. */
  private var bytesReadAfterMark = 0

  /** The number of bytes currently available. */
  private var bytesAvailable = 0

  /** A flag whether this stream has been completed. */
  private var contentCompleted = false

  /**
   * Appends the content stored in the given ''ArraySource'' object to this
   * stream.
   * @param data the source object to be added
   * @return a reference to this stream
   * @throws IllegalStateException if the stream is already complete
   */
  def append(data: ArraySource): DynamicInputStream = {
    if (completed) {
      throw new IllegalStateException("Cannot add data to a completed stream!")
    }

    checkMarkReadLimit()
    ensureCapacity()
    chunks(appendChunk) = data
    appendChunk = increaseChunkIndex(appendChunk)
    bytesAvailable += data.length
    this
  }

  /**
   * Appends the content of the given array to this stream. Note that for
   * efficiency reasons no copy of the array is created. So the array must not
   * be modified afterwards.
   * @param data the data array to be appended
   * @return a reference to this stream
   * @throws IllegalStateException if the stream is already complete
   */
  def append(data: Array[Byte]): DynamicInputStream =
    append(arraySourceFor(data))

  /**
   * Clears the whole content of this stream. After this operation, the
   * stream is empty and can again be filled an reused.
   */
  def clear(): Unit = {
    bytesAvailable = 0
    currentChunk = 0
    appendChunk = 0
    currentPosition = 0
    markedChunk = UndefinedMarkIndex
    contentCompleted = false
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
   * Searches for a given byte in the amount of data currently available. If
   * the byte is found, the current position of the stream is directly after
   * this byte. Otherwise, all data currently available has been read. This
   * method is introduced as a more efficient way of scanning for data rather
   * than reading the stream byte-wise.
   * @param b the byte to be searched
   * @return a flag whether the byte was found in the data available
   */
  def find(b: Byte): Boolean = {
    var found = false
    process(available()) { (src, pos, length, count) =>
      val hit = src.data.indexOf(b, pos)
      if(hit < 0) hit
      else {
        found = true
        hit + 1
      }
    }
    found
  }

  /**
   * Returns a flag whether this stream implementation supports mark
   * operations. This is the case; therefore, result is '''true'''.
   */
  override val markSupported = true

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
      val buf = new Array[Byte](1)
      read(buf, 0 ,1)
      buf(0)
    } else -1
  }

  override def read(b: Array[Byte]): Int = read(b, 0, b.length)

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val readLength = checkAvailable(len)
    if (readLength == 0 && completed) -1
    else {
      process(readLength) { (src, pos, length, offset) =>
        System.arraycopy(src.data, src.offset + pos, b, offset + off, length)
        -1
      }
    }
  }

  /**
   * @inheritdoc This stream implementation supports mark and reset operations.
   *             The current position in the stream is marked. If data is read
   *             beyond the specified limit, the marked position is lost.
   * @param readlimit the number of bytes to keep before a reset
   */
  override def mark(readlimit: Int): Unit = {
    markedChunk = currentChunk
    markedPosition = currentPosition
    bytesReadAfterMark = 0
    markReadLimit = readlimit
  }

  /**
   * @inheritdoc This implementation restores the position selected by the
   *             previous mark operation. If there was none, an exception
   *             is thrown.
   */
  override def reset(): Unit = {
    if(markedChunk == UndefinedMarkIndex) {
      throw new IOException("reset without mark!")
    }

    currentChunk = markedChunk
    currentPosition = markedPosition
    bytesAvailable += bytesReadAfterMark
    bytesReadAfterMark = 0
  }

  /**
   * @inheritdoc The skip() method is implemented here explicitly in a more
   *             efficient way. ''Note'': Only skip sizes in the range of an
   *             Int are supported!
   */
  override def skip(n: Long): Long =
    process(checkAvailable(n.toInt)) { (_,_,_,_) => -1 }

  /**
   * Updates internal counters to reflect that the given number of bytes was
   * read.
   * @param count the number of bytes which has been read
   */
  private def bytesRead(count: Int): Unit = {
    bytesAvailable -= count
    bytesReadAfterMark += count
  }

  /**
   * A type definition for a function used by the ''process()'' method. This
   * function is invoked when processing the stream. The arguments have the
   * following meaning: the current chunk, the current position in this chunk,
   * the number of bytes to process in this chunk, the number of bytes
   * already processed in the current operation. The return value is the new
   * current position in this chunk; a value of -1 means that processing should
   * continue with the next chunk; all other values terminate processing at
   * this position.
   */
  private type ProcessingFunc = (ArraySource, Int, Int, Int) => Int

  /**
   * Processes data from this stream. This method processes the given number of
   * bytes. For each chunk encountered during processing the specified
   * processing function is called. The function may terminate the processing
   * by returning an end position. Otherwise, processing continues until the
   * maximum number of bytes to be processed is reached (or no more data is
   * available). The return value is the number of bytes processed.
   * @param count the maximum number of bytes to be processed
   * @param func the processing function
   * @return the number of bytes that have been processed
   */
  private def process(count: Int)(func: ProcessingFunc): Int = {
    var bytesProcessed = 0
    var bytesToProcess = count
    var done = false

    while (bytesProcessed < count && !done) {
      val chunkReadLength = math.min(chunks(currentChunk).length - currentPosition, bytesToProcess)
      if (chunkReadLength > 0) {
        val pos = func(chunks(currentChunk), currentPosition, chunkReadLength, bytesProcessed)
        val processedInChunk = if (pos < 0) chunkReadLength
        else pos - currentPosition
        currentPosition += processedInChunk
        bytesToProcess -= processedInChunk
        bytesProcessed += processedInChunk
        done = pos >= 0
      } else {
        currentChunk = increaseChunkIndex(currentChunk)
        currentPosition = 0
      }
    }

    bytesRead(bytesProcessed)
    bytesProcessed
  }

  /**
   * Ensures that the stream has sufficient capacity to add another chunk.
   * If necessary, the internal array is enlarged. (Every time the capacity is
   * increased, the array's size is doubled.)
   */
  private def ensureCapacity(): Unit = {
    val chunkIndex = if (markedChunk != UndefinedMarkIndex) markedChunk
    else currentChunk

    if (appendChunk == chunkIndex && available() > 0) {
      val newChunks = copyChunks(chunkIndex)
      if (markedChunk != UndefinedMarkIndex) {
        val delta = if (markedChunk <= currentChunk) currentChunk - markedChunk
        else capacity + currentChunk - markedChunk
        markedChunk = 0
        currentChunk = delta
      } else {
        currentChunk = 0
      }
      appendChunk = chunks.length
      chunks = newChunks
    }
  }

  /**
   * Checks whether the read limit specified when calling mark() has been
   * reached. If this is the case, the member fields associated with a mark
   * operation are reset. It is then no longer possible to reset the stream to
   * this position.
   */
  private def checkMarkReadLimit() {
    if (markedChunk != UndefinedMarkIndex && bytesReadAfterMark >= markReadLimit) {
      markedChunk = UndefinedMarkIndex
    }
  }

  /**
   * Creates a new array with chunks and copies the content of the old array
   * into it.
   * @param startChunkIndex the index of the chunks where to start the copying
   * @return the new array with chunks
   */
  private def copyChunks(startChunkIndex: Int): Array[ArraySource] = {
    val newChunks = new Array[ArraySource](chunks.length * 2)
    var orgIndex = startChunkIndex
    for (i <- chunks.indices) {
      newChunks(i) = chunks(orgIndex)
      orgIndex = increaseChunkIndex(orgIndex)
    }
    newChunks
  }

  /**
   * Convenience method determining the minimum of the passed in length and the
   * number of bytes available. This operation is needed in multiple places.
   * @param len the length to be checked
   * @return the checked length
   */
  private def checkAvailable(len: Int): Int = math.min(len, bytesAvailable)

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
