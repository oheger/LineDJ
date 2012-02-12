package de.oliver_heger.test.actors

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * <p>A default implementation of the {@code TempFileFactory} interface.</p>
 * <p>This implementation uses the standard functionality provided by the
 * {@code java.io.File} class to create temporary files in the current user's
 * temporary directory.</p>
 */
class TempFileFactoryImpl(prefix: String, suffix: String)
  extends TempFileFactory {
  /**
   * Constant for the default prefix for temporary files created by this
   * factory.
   */
  val DefaultFilePrefix = "TempFileFactoryImpl"

  /**
   * Constant for the default suffix for temporary files created by this
   * factory.
   */
  val DefaultFileSuffix = ".tmp"

  /** The file prefix used by this factory.*/
  val filePrefix = if (prefix == null) DefaultFilePrefix else prefix

  /** The file suffix used by this factory.*/
  val fileSuffix = if (suffix == null) DefaultFileSuffix else suffix

  /**
   * Creates a new instance using default values for the file prefix and suffix.
   */
  def this() = this(null, null)

  /**
   * Creates a new temporary file.
   * @return the temporary file
   */
  def createFile(): TempFile = {
    val file = File.createTempFile(filePrefix, fileSuffix)
    file.deleteOnExit()
    TempFileImpl(file)
  }
}

/**
 * A simple implementation of the {@code TempFile} interface which is backed by
 * a File object.
 */
private case class TempFileImpl(file: File) extends TempFile {
  def inputStream() = new FileInputStream(file)

  def outputStream() = new FileOutputStream(file)

  def delete() = file.delete()
}