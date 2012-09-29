package de.oliver_heger.splaya.mp3

import java.io.FilterInputStream
import java.io.InputStream

/**
 * A specialized input stream implementation which records the last portion
 * read from an underlying stream.
 *
 * This stream implementation is useful to deal with information which is known
 * to be located at the end of a stream. While reading bytes from the underlying
 * stream, a given number of bytes is kept in an internal buffer. This buffer
 * can then be queried after the whole stream was read. It contains the last
 * bytes read from the original input stream.
 *
 * @param in the underlying input stream
 * @param tailSize the size of the tail buffer
 */
class TailStream(in: InputStream, val tailSize: Int) extends FilterInputStream(in) {
  /** The buffer in which the tail data is stored. */
  private val tailBuffer = new Array[Byte](tailSize)

  /** A copy of the internal tail buffer used for mark() operations. */
  private var markBuffer: Array[Byte] = _

  /** The number of bytes that have been read so far. */
  private var bytesRead = 0L

  /** The number of bytes read at the last mark() operation. */
  private var markBytesRead = 0L

  /** The current index into the tail buffer. */
  private var currentIndex = 0

  /** A copy of the current index used for mark() operations. */
  private var markIndex = 0

  /**
   * @inheritdoc This implementation adds the read byte to the internal tail
   * buffer.
   */
  override def read(): Int = {
    val c = super.read()
    if (c != -1) {
      appendByte(c.toByte)
    }
    c
  }

  /**
   * @inheritdoc This implementation delegates to the underlying stream and
   * then adds the correct portion of the read buffer to the internal tail
   * buffer.
   */
  override def read(buf: Array[Byte]): Int = {
    val read = super.read(buf)
    if (read > 0) {
      appendBuf(buf, 0, read)
    }
    read
  }

  /**
   * @inheritdoc This implementation delegates to the underlying stream and
   * then adds the correct portion of the read buffer to the internal tail
   * buffer.
   */
  override def read(buf: Array[Byte], ofs: Int, length: Int): Int = {
    val read = super.read(buf, ofs, length)
    if (read > 0) {
      appendBuf(buf, ofs, read)
    }
    read
  }

  /**
   * @inheritdoc This implementation saves the internal state including the
   * content of the tail buffer so that it can be restored when ''reset()''
   * is called later.
   */
  override def mark(limit: Int) {
    markBuffer = new Array(tailSize)
    System.arraycopy(tailBuffer, 0, markBuffer, 0, tailSize)
    markIndex = currentIndex
    markBytesRead = bytesRead
  }

  /**
   * @inheritdoc This implementation restores this stream's state to the state
   * when ''mark()'' was called the last time. If ''mark()'' has not been
   * called before, this method has no effect.
   */
  override def reset() {
    if (markBuffer != null) {
      System.arraycopy(markBuffer, 0, tailBuffer, 0, tailSize)
      currentIndex = markIndex
      bytesRead = markBytesRead
    }
  }

  /**
   * Returns an array with the last data read from the underlying stream. If
   * the underlying stream contained more data than the ''tailSize''
   * constructor argument, the returned array has a length of ''tailSize''.
   * Otherwise, its length equals the number of bytes read.
   * @return an array with the last data read from the underlying stream
   */
  def tail: Array[Byte] = {
    val size = math.min(tailSize, bytesRead).toInt
    val result = new Array[Byte](size)
    System.arraycopy(tailBuffer, currentIndex, result, 0, size - currentIndex)
    System.arraycopy(tailBuffer, 0, result, size - currentIndex, currentIndex)
    result
  }

  /**
   * Adds the given byte to the internal tail buffer.
   * @param b the byte to be added
   */
  private def appendByte(b: Byte) {
    tailBuffer(currentIndex) = b
    currentIndex += 1
    if (currentIndex >= tailSize) {
      currentIndex = 0
    }
    bytesRead += 1
  }

  /**
   * Adds the content of the given buffer to the internal tail buffer.
   * @param buf the buffer
   * @param ofs the start offset in the buffer
   * @param length the number of bytes to be copied
   */
  private def appendBuf(buf: Array[Byte], ofs: Int, length: Int) {
    if (length >= tailSize) {
      replaceTailBuffer(buf, ofs, length)
    } else {
      copyToTailBuffer(buf, ofs, length)
    }

    bytesRead += length
  }

  /**
   * Replaces the content of the internal tail buffer by the last portion of the
   * given buffer. This method is called if a buffer was read from the
   * underlying stream whose length is larger than the tail buffer.
   * @param buf the buffer
   * @param ofs the start offset in the buffer
   * @param length the number of bytes to be copied
   */
  private def replaceTailBuffer(buf: Array[Byte], ofs: Int, length: Int) {
    System.arraycopy(buf, ofs + length - tailSize, tailBuffer, 0, tailSize)
    currentIndex = 0
  }

  /**
   * Copies the given buffer into the internal tail buffer at the current
   * position. This method is called if a buffer is read from the underlying
   * stream whose length is smaller than the tail buffer. In this case the
   * tail buffer is only partly overwritten.
   * @param buf the buffer
   * @param ofs the start offset in the buffer
   * @param length the number of bytes to be copied
   */
  private def copyToTailBuffer(buf: Array[Byte], ofs: Int, length: Int) {
    val remaining = tailSize - currentIndex
    val size1 = math.min(remaining, length)
    System.arraycopy(buf, ofs, tailBuffer, currentIndex, size1)
    System.arraycopy(buf, ofs + size1, tailBuffer, 0, length - size1)
    currentIndex = (currentIndex + length) % tailSize
  }
}
