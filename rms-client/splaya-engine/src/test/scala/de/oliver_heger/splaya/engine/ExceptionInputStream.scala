package de.oliver_heger.splaya.engine

import java.io.InputStream
import java.io.IOException

/**
 * A helper stream class which throws an exception after the full content was
 * read.
 * @param content the content of the stream
 */
class ExceptionInputStream(content : String) extends InputStream {
  /** The content of the stream as an array. */
  private val contentArray = content.getBytes()

  /** The number of bytes read. */
  private var readCount = 0

  /**
   * Reads the next byte from the stream. If the stream's content has already
   * been read fully, an exception is thrown.
   */
  override def read(): Int = {
    if (readCount >= contentArray.length) {
      throw new IOException("Exception from ExceptionInputStream!");
    }
    val res = contentArray(readCount)
    readCount += 1
    res
  }
}
