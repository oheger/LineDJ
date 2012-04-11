package de.oliver_heger.splaya.engine.io
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/**
 * <p>A class providing functionality for the implementation of streams supporting
 * the mark/reset feature.</p>
 */
class StreamResetHelper(factory: TempFileFactory) {
  /** The current temporary file for input.*/
  private var tempIn: TempFile = _

  /** The current temporary file for output. */
  private var tempOut: TempFile = _

  /** The current input stream used for a reset operation.*/
  private var streamIn: InputStream = _

  /** The current output stream used for a mark operation.*/
  private var streamOut: OutputStream = _

  /**
   * Returns data already read if a mark operation has been performed. This method
   * should be called by a stream's implementation of the {@code read()} method.
   * If no mark has been performed so far, this method does nothing and returns
   * 0. Otherwise data which has already been read is provided again. The
   * return value is the number of bytes which could be (re-) read. If it is
   * less than the length parameter, a stream implementation has to read the
   * remaining data from its original data.
   * @param buf the target buffer
   * @param ofs the offset into the buffer
   * @param len the number of bytes to read
   * @return the actual number of bytes read
   */
  def read(buf: Array[Byte], ofs: Int, len: Int): Int = {
    if (tempIn != null) {
      val count = streamIn.read(buf, ofs, len)
      if (count == -1) {
        closeIn()
        0
      } else {
        count
      }
    } else {
      0
    }
  }

  /**
   * Notifies this object that data has been read from the underlying stream.
   * This method has to be called for each data array to be returned to client
   * code. If a mark operation has been performed, the data is recorded so that
   * it can be read again after reset() was called.
   */
  def push(buf: Array[Byte], ofs: Int, len: Int) {
    if (marked) {
      streamOut.write(buf, ofs, len)
    }
  }

  /**
   * Marks the current position. A following call of reset() will reset the
   * position.
   */
  def mark() {
    closeOut()

    tempOut = factory.createFile()
    streamOut = tempOut.outputStream()
  }

  /**
   * Resets the stream to the position where mark() was called. If mark() has
   * not been called before, an exception is thrown.
   */
  def reset() {
    if (!marked) {
      throw new IOException("Reset called without mark!")
    }
    closeIn()

    tempIn = tempOut
    streamOut.close()
    streamOut = null
    tempOut = null
    streamIn = tempIn.inputStream()
  }

  /**
   * Returns a flag whether mark() has been called. If this method returns
   * <b>true</b>, it is safe to call reset().
   * @return <b>true</b> if a valid mark has been set, <b>false</b> otherwise
   */
  def marked(): Boolean = tempOut != null

  /**
   * Closes this helper object. This method should be called when the associated
   * stream is closed. It ensures that all temporary files which are currently
   * open are removed.
   */
  def close() {
    closeOut()
    closeIn()
  }

  /**
   * Closes the input file if it exists.
   */
  private def closeIn() {
    if (tempIn != null) {
      streamIn.close()
      tempIn.delete()
      streamIn = null
      tempIn = null
    }
  }

  /**
   * Closes the output file if it exists.
   */
  private def closeOut() {
    if (tempOut != null) {
      streamOut.close()
      tempOut.delete()
      streamOut = null
      tempOut = null
    }
  }
}
