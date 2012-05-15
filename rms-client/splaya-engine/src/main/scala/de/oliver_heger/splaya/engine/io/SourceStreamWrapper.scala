package de.oliver_heger.splaya.engine.io

import java.io.InputStream
import org.slf4j.LoggerFactory

/**
 * A specialized stream implementation which wraps temporary files streamed
 * from the source medium.
 *
 * During streaming data from the source medium is copied to a bunch of
 * temporary files. In order to further process this data and to extract the
 * original data, a stream has to be provided. This stream implementation is
 * initialized with a queue of temporary files which contain the data of the
 * represented stream (depending on the size of the represented stream the data
 * may be spread over multiple temporary files.)
 *
 * Implementation note: This class is not thread-safe. It can only be accessed
 * from a single thread.
 *
 * @param resetHelper the helper object for managing ''reset()'' operations
 * @param wrappedStream the wrapped input stream
 * @param streamLength the (initial) length of the wrapped input stream (it
 * can be changed later using the ''changeLength()'' method)
 * @param bufferManager the object managing temporary files
 */
class SourceStreamWrapper(resetHelper: StreamResetHelper,
  wrappedStream: InputStream, private var streamLength: Long,
  bufferManager: SourceBufferManager) extends InputStream {
  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[SourceStreamWrapper])

  /** Stores the reset helper object.*/
  private[engine] val streamResetHelper = resetHelper

  /** The current input stream.*/
  private var stream: InputStream = wrappedStream

  /** The current read position in the stream.*/
  private var position = 0L

  /** The last mark position. */
  private var markPosition = 0L

  /** A flag indicating that this stream has been read completely. */
  private var endOfStream = false

  /** A flag whether a stream read event has already been sent. */
  private var readEventSent = false

  /**
   * Auxiliary constructor which creates a default {@code StreamResetHelper}.
   * @param factory the factory for temporary files
   * @param wrappedStream the wrapped input stream
   * @param length the length of the wrapped stream
   * @param bufferManager the queue for obtaining temporary files
   */
  def this(factory: TempFileFactory, wrappedStream: InputStream, length: Long,
    bufferManager: SourceBufferManager) = this(new StreamResetHelper(factory),
    wrappedStream, length, bufferManager)

  /**
   * Returns the length of this stream.
   * @return the length of this stream
   */
  def length = streamLength

  /**
   * Returns the current position in this stream.
   * @return the current position
   */
  def currentPosition: Long = position

  /**
   * Returns a flag whether the end of this stream was reached.
   * @return the end of stream flag
   */
  def isEndOfStream = endOfStream

  /**
   * Returns a flag whether mark operations are supported by this stream. This
   * is the case.
   * @return a flag whether mark operations are supported
   */
  override def markSupported = true

  /**
   * Reads data from this stream. The data is read from the current input stream.
   * If it is exhausted, a new temporary file is queried from the queue and opened.
   * @param buf the target buffer
   * @param ofs the offset into the buffer
   * @param len the number of bytes to read
   * @return the number of bytes read
   */
  override def read(buf: Array[Byte], ofs: Int, len: Int): Int = {
    if (endOfStream) -1
    else {
      val maxlen = scala.math.min(len, length - currentPosition).toInt
      var read = resetHelper.read(buf, ofs, maxlen)

      if (read < len) {
        fetchCurrentStream()
        val streamRead = currentStream.read(buf, ofs + read, maxlen - read)
        if (streamRead == -1) {
          closeCurrentStream()
        } else {
          read += streamRead
        }
      }

      resetHelper.push(buf, ofs, read)
      if (position == length && read == 0) {
        endOfStream = true
        sendStreamReadEventIfNecessary()
        -1
      } else {
        updatePosition(position + read)
        read
      }
    }
  }

  /**
   * Reads a single byte from this stream. This implementation delegates to
   * the other read() method.
   * @return the byte read from the stream or -1 if EOF is reached
   */
  def read(): Int = {
    val buf = new Array[Byte](1)
    var result = 0
    do {
      result = read(buf, 0, 1)
    } while (result == 0)
    if (result == -1) -1
    else buf(0)
  }

  /**
   * Marks the current position in this stream. This implementation delegates
   * to the helper object.
   * @param limit the mark limit (ignored)
   */
  override def mark(limit: Int) {
    log.info("mark() called with limit of {}.", limit)
    markPosition = currentPosition
    resetHelper.mark()
  }

  /**
   * Resets this stream to the last position mark() was called. This
   * implementation delegates to the helper object.
   */
  override def reset() {
    resetHelper.reset()
    updatePosition(markPosition)
    endOfStream = false
  }

  /**
   * Closes this stream. This implementation does not close the underlying
   * stream. This cannot be done here because the underlying stream is shared
   * between multiple stream wrappers. Use ''closeCurrentStream()'' if really
   * all resources should be released.
   */
  override def close() {
    resetHelper.close()
  }

  /**
   * Closes this stream including the underlying stream.
   */
  def closeCurrentStream() {
    if (currentStream != null) {
      currentStream.close()
      stream = null
    }
    close()
  }

  /**
   * Changes the length of this stream. (Usually, the length is decreased.)
   * @param newLength the new length of this stream
   */
  def changeLength(newLength: Long) = {
    streamLength = newLength
  }

  /**
   * Returns the current input stream used by this wrapper. The next audio file
   * starts at the current position in this stream. Result may be <b>null</b>
   * if the current stream has been read completely.
   * @return the current input stream
   */
  private[engine] def currentStream: InputStream = stream

  /**
   * Obtains the current input stream. If necessary, a new temporary file is
   * obtained from the queue and opened. This may block until new data becomes
   * available.
   */
  private def fetchCurrentStream() {
    if (currentStream == null) {
      val temp = bufferManager.next()
      stream = temp.inputStream()
    }
  }

  /**
   * Updates the current position in this stream. The buffer manager is
   * notified, too.
   * @param pos the new position
   */
  private def updatePosition(pos: Long) {
    position = pos
    bufferManager.updateCurrentStreamReadPosition(pos)
  }

  /**
   * Sends a stream read event to the buffer manager if this is the first time
   * the stream was completely read.
   */
  private def sendStreamReadEventIfNecessary() {
    if (!readEventSent) {
      bufferManager.streamRead(length)
      readEventSent = true
    }
  }
}
