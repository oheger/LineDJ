package de.oliver_heger.splaya.engine.io
import scala.actors.Actor
import scala.collection.mutable.Queue
import de.oliver_heger.splaya.engine.msg.Gateway
import de.oliver_heger.splaya.engine.msg.ReadChunk
import java.io.IOException

/**
 * A default implementation of the ''SourceBufferManager'' interface.
 *
 * This implementation is backed by a queue. Also, when temporary
 * files are fetched from the buffer exhausted files are removed, and a
 * notification message can be sent. This can cause another actor to fill the
 * buffer again.
 *
 * Implementation note: This class is not thread-safe!
 */
class SourceBufferManagerImpl extends SourceBufferManager {
  /** The underlying queue.*/
  private val queue = Queue.empty[TempFile]

  /** The current temporary file.*/
  private var currentFile: TempFile = _

  /** The read position in the current input stream. */
  private var streamPosition = 0L

  /** The size of the temporary files in total. */
  private var tempFileSize = 0L

  /**
   * Returns the current read position in the current stream.
   */
  def currentStreamReadPosition: Long = streamPosition

  /**
   * Updates the read position of the current input stream.
   */
  def updateCurrentStreamReadPosition(pos: Long) {
    streamPosition = pos
  }

  /**
   * Notifies this object that the current input stream has been fully read.
   * This implementation updates the buffer size.
   */
  def streamRead(length: Long) {
    tempFileSize -= length
    updateCurrentStreamReadPosition(0)
  }

  /**
   * Flushes this manager. This implementation resets this manager's state. It
   * also frees all used space on the local disk by iterating over remaining
   * files in the queue and deleting them.
   */
  def flush() {
    if (currentFile != null) {
      currentFile.delete()
      currentFile = null
    }
    queue foreach (_.delete())
    queue.clear()
    updateCurrentStreamReadPosition(0)
    tempFileSize = 0
  }

  /**
   * Returns the first entry from this buffer. If the buffer is empty, an
   * exception is thrown.
   * @return the first entry from this buffer
   * @throws IOException if there is no entry
   */
  def next(): TempFile = {
    if (currentFile != null) {
      currentFile.delete()
      Gateway ! Gateway.ActorSourceRead -> ReadChunk
    }

    if (queue.isEmpty) {
      throw new IOException("Buffer is empty!")
    }
    currentFile = queue.dequeue()
    currentFile
  }

  /**
   * Appends the specified temporary file to this buffer.
   * @param file the file to add
   */
  def append(file: TempFile): Unit = {
    queue += file
    tempFileSize += file.length
  }

  /**
   * Returns the size of the temporary buffer in bytes.
   * @return the current size of the temporary buffer
   */
  def bufferSize: Long = tempFileSize - streamPosition
}
