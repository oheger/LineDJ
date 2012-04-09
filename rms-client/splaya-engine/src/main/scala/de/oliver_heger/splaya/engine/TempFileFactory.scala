package de.oliver_heger.splaya.engine
import java.io.File

/**
 * <p>A trait for the creation of a temporary file.</p>
 * <p>An object implementing this trait can be used by clients which need to
 * create temporary files, but do not care about details like directories or
 * file names.</p>
 */
trait TempFileFactory {
  def createFile(): TempFile
}
