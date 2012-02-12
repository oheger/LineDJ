package de.oliver_heger.splaya.engine

/**
 * <p>A trait for controlling source data copied to a temporary buffer.</p>
 * <p>During audio playback audio data is streamed from an arbitrary source to
 * a temporary buffer on the local hard disk. This trait defines the interface
 * of a controller for the temporary files which comprise the buffer. It is
 * possible to append new temporary files and to access the first one.</p>
 * <p>Basically this trait is similar to a queue: new entries are appended to
 * the tail, the current entry is extracted from the head. A typical
 * implementation will also have some event notification facilities to notify
 * involved actors that files from the buffer have been read.</p>
 */
trait SourceBufferManager {
  /**
   * Closes this manager and the associated buffer. This method should be called
   * at the end of the application. It ensures that all remaining temporary
   * files are removed from disk.
   */
  def close() : Unit

  /**
   * Returns the next temporary file from the buffer. Note that this operation
   * may block until a new file has been fully written to the buffer.
   * @return the next temporary file
   */
  def next(): TempFile

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
}
