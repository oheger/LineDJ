package de.oliver_heger.splaya.tsthlp
import java.io.File
import java.io.PrintStream
import java.io.FileOutputStream

/**
 * A trait which can be used by test classes which need to operate on
 * temporary files.
 *
 * This trait provides functionality for creating temporary files. All files
 * created through this trait are recorded so that they can be removed again
 * later. The method for removing all temporary files should be called in the
 * ''tearDown()'' method of the test class.
 */
trait TestFileSupport {
  /** A list with the temporary files created by methods of this trait. */
  private var tempFiles: List[File] = List.empty

  /**
   * Creates a new temporary file. The file is recorded in an internal list.
   * Calling ''removeTempFiles()'' will remove it.
   * @return the temporary file
   * @throws IOException if an IO error occurs
   */
  def createTempFile(): File = {
    val file = File.createTempFile("TestFile", "tmp")
    tempFiles = file :: tempFiles
    file
  }

  /**
   * Creates a temporary file and writes it content. This method works like the
   * method with the same name. After the ''File'' object is created, an
   * output stream is opened on it. Then the passed in function is called which
   * can write the file's content.
   * @param f a function for writing the content of the file
   * @return the newly created temporary file
   * @throws IOException if an IO error occurs
   */
  def createTempFile(f: PrintStream => Unit): File = {
    val file = createTempFile()
    val out = new PrintStream(new FileOutputStream(file))
    try {
      f(out)
    } finally {
      out.close()
    }
    file
  }

  /**
   * Removes all temporary files which have been created through the methods
   * provided by this trait.
   */
  def removeTempFiles() {
    tempFiles foreach (_.delete())
    tempFiles = List.empty
  }
}
