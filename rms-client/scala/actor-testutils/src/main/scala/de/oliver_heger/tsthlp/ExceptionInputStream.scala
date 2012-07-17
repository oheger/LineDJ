package de.oliver_heger.tsthlp

import java.io.InputStream
import java.io.IOException

/**
 * A helper stream class which throws an exception after the full content was
 * read.
 * @param content the content of the stream
 */
class ExceptionInputStream(content: String) extends InputStream {
  /** The content of the stream as an array. */
  private val contentArray = content.getBytes()

  /** The number of bytes read. */
  private var readCount = 0

  /**
   * Reads the next byte from the stream. If the stream's content has already
   * been read fully, an exception is thrown.
   */
  override def read(): Int = {
    throwExceptionIfPositionReached()
    val res = contentArray(readCount)
    readCount += 1
    res
  }

  /**
   * Reads a whole buffer from this stream. This method has to be overridden
   * because the base implementation catches the exception thrown by the
   * simple read() method.
   */
  override def read(buf: Array[Byte], ofs: Int, len: Int): Int = {
    val count = super.read(buf, ofs, len)
    throwExceptionIfPositionReached()
    count
  }

  /**
   * Checks whether the exception has to be thrown. This is the case if the
   * content of the stream has been fully read.
   */
  private def throwExceptionIfPositionReached() {
    if (readCount >= contentArray.length) {
      throw new IOException("Exception from ExceptionInputStream!");
    }
  }
}
