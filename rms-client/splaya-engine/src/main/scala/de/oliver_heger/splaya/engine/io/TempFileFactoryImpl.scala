package de.oliver_heger.splaya.engine.io

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.slf4j.LoggerFactory

/**
 * A default implementation of the {@code TempFileFactory} interface.
 *
 * This implementation uses the standard functionality provided by the
 * `java.io.File` class to create temporary files in the current user's
 * temporary directory.
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

  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[TempFileFactoryImpl])

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
    log.info("Creating temporary file: {}.", file.getAbsolutePath())
    TempFileImpl(file)
  }
}

/**
 * A simple implementation of the {@code TempFile} interface which is backed by
 * a File object.
 */
private case class TempFileImpl(file: File) extends TempFile {
  /** The logger. */
  private val log = LoggerFactory.getLogger(classOf[TempFileImpl])

  def inputStream() = new FileInputStream(file)

  def outputStream() = new FileOutputStream(file)

  def length = file.length

  def delete() = {
    log.info("Removing temporary file: {}.", file.getAbsolutePath())
    val success = file.delete()
    if (!success) {
      log.warn("Could not remove temporary file!")
    }
    success
  }
}
