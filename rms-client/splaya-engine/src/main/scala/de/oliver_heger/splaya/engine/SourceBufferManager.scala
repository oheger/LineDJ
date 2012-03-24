package de.oliver_heger.splaya.engine

/**
 * A trait for controlling source data copied to a temporary buffer.
 *
 * During audio playback audio data is streamed from an arbitrary source to
 * a temporary buffer on the local hard disk. This trait defines the interface
 * of a controller for the temporary files which comprise the buffer. It is
 * possible to append new temporary files and to access the first one. The
 * current size of the buffer is monitored, too.
 *
 * Basically this trait is similar to a queue: new entries are appended to
 * the tail, the current entry is extracted from the head. A typical
 * implementation will also have some event notification facilities to notify
 * involved actors that files from the buffer have been read.
 */
trait SourceBufferManager {
  /**
   * Performs a ''flush'' operation on this manager and the associated buffer.
   * This method clears the state of this manager and ensures that all remaining
   * temporary files are removed from disk. After calling it, the
   * ''SourceBufferManager'' can be reused and filled with new content.
   */
  def flush(): Unit

  /**
   * Returns the next temporary file from the buffer. Note that this operation
   * may block until a new file has been fully written to the buffer.
   * @return the next temporary file
   */
  def next(): TempFile

  /**
   * Returns the current read position in the current stream. This is the input
   * stream which is used to read data from the temporary buffer.
   * @return the current read position from the current input stream
   */
  def currentStreamReadPosition: Long

  /**
   * Updates the read position of the current input stream. This method is
   * called when data from the stream is read. It allows this object to keep
   * track of the available data in the buffer.
   * @param pos the new read position
   */
  def updateCurrentStreamReadPosition(pos: Long): Unit

  /**
   * Notifies this object that the current input stream has been fully read.
   * This allows the manager to update its status so it can keep track of the
   * data available in the buffer.
   * @param length the length of the stream
   */
  def streamRead(length: Long): Unit

  /**
   * Adds a new temporary file to the buffer.
   * @param file the file to be added
   */
  def append(file: TempFile): Unit

  /**
   * Operator for adding a new temporary file to the buffer. This is a short cut
   * for the {@code append()} method.
   * @param file the file to be added
   */
  def +=(file: TempFile) {
    append(file)
  }

  /**
   * Returns the size of the temporary buffer in bytes. This is the number of
   * bytes stored in the temporary files managed by this object minus the bytes
   * already read from input streams.
   * @return the current size of the temporary buffer
   */
  def bufferSize: Long
}
