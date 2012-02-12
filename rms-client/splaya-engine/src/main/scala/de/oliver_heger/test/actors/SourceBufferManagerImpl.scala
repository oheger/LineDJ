package de.oliver_heger.test.actors
import scala.actors.Actor
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * <p>A default implementation of the {@code SourceBufferManager} interface.</p>
 * <p>This implementation is backed by a blocking queue. Also, when temporary
 * files are fetched from the buffer exhausted files are removed, and a
 * notification message can be sent. This can cause another actor to fill the
 * buffer again.</p>
 */
class SourceBufferManagerImpl extends SourceBufferManager {
  /** The underlying queue.*/
  private val queue: BlockingQueue[TempFile] = new LinkedBlockingQueue

  /** The current temporary file.*/
  private var currentFile: TempFile = _

  /**
   * Closes this manager. This implementation iterates over all remaining files
   * in the queue and deletes them.
   */
  def close() {
    val it = queue.iterator()
    while (it.hasNext()) {
      it.next().delete()
    }
  }

  /**
   * Returns the first entry from this buffer. This operation may block until
   * data becomes available.
   * @return the first entry from this buffer
   */
  def next(): TempFile = {
    if (currentFile != null) {
      currentFile.delete()
      Gateway ! Gateway.ActorSourceRead -> ReadChunk
    }

    currentFile = queue.take()
    currentFile
  }

  /**
   * Appends the specified temporary file to this buffer.
   * @param file the file to add
   */
  def append(file: TempFile): Unit = {
    queue.put(file)
  }
}