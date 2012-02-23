package de.oliver_heger.splaya.engine
import java.io.OutputStream
import java.io.InputStream

/**
 * A trait that represents a temporary file.
 *
 * This trait defines some methods for the interaction with a temporary
 * file. It is possible to open both input and output streams, and to remove the
 * whole file. The idea behind this treat is that there could be multiple
 * different implementations, not only file-based, but also in-memory or
 * whatever.
 */
trait TempFile {
  /**
   * Opens an output file to the temporary file represented by this object.
   * @return an output stream to the temporary file
   */
  def outputStream(): OutputStream

  /**
   * Opens an input stream to the temporary file represented by this object.
   * @return an input stream to the temporary file
   */
  def inputStream(): InputStream

  /**
   * Removes this temporary file. Note: All streams which have been opened must
   * have been removed.
   * @return a flag whether the file could be removed
   */
  def delete(): Boolean

  /**
   * Returns length of this temporary file.
   * @return the length of this file
   */
  def length: Long
}
