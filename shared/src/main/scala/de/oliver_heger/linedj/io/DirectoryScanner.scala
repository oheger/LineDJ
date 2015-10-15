/*
 * Copyright 2015 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.io

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, FileVisitResult, SimpleFileVisitor, Path}
import java.util.Locale

import scala.collection.mutable.ListBuffer

/**
 * A data class representing information about a file encountered by a
 * [[DirectoryScanner]].
 *
 * @param path the path pointing to the file
 * @param size the file size (in bytes)
 */
case class FileData(path: Path, size: Long)

/**
 * A data class returned as result of a directory scan operation by
 * [[DirectoryScanner]].
 *
 * This class mainly stores lists of the elements encountered during a scan
 * operation. Files are separated from directories because they are often
 * treated differently. The order of the lists reflect the order in which the
 * elements were encountered.
 *
 * @param directories a list with the encountered directories
 * @param files a list with the encountered files
 */
case class ScanResult(directories: Seq[Path], files: Seq[FileData])

/**
 * A generic scanner implementation for reading the content of a directory
 * structure.
 *
 * This class is a thin wrapper over the ''walkFileTree'' API from Java. It
 * allows retrieving all files and directories found in a given structure. It
 * is possible to specify a set of file extensions which should be excluded.
 *
 * @param excludedExtensions a set with file extensions to be excluded; these
 *                           must be in upper case
 */
class DirectoryScanner(val excludedExtensions: Set[String]) {
  def this() = this(Set.empty)

  /**
   * Scans the specified root directory and returns a ''ScanResult'' object
   * with the results. Note that the root directory is also contained in the
   * list of found directories.
   * @param directory the root directory to be scanned
   * @return an object with the results of the scan operation
   */
  @throws(classOf[java.io.IOException])
  def scan(directory: Path): ScanResult = {
    val visitor = new ScanVisitor(excludedExtensions)
    Files.walkFileTree(directory, visitor)
    ScanResult(directories = visitor.directories, files = visitor.files)
  }
}

private object ScanVisitor {
  /** Constant for an undefined file extension. */
  private val NoExtension = ""

  /** Constant for the extension delimiter character. */
  private val Dot = '.'

  /**
   * Extracts the file extension from the given path.
   * @param path the path
   * @return the extracted extension
   */
  def extractExtension(path: Path): String = {
    val fileName = path.getFileName.toString
    val pos = fileName lastIndexOf Dot
    if (pos >= 0) fileName.substring(pos + 1)
    else NoExtension
  }
}

/**
 * A visitor implementation which is passed to the file walker API and collects
 * the encountered files and directories.
 *
 * @param exclusions a set with the file extensions to be excluded
 */
private class ScanVisitor(exclusions: Set[String]) extends SimpleFileVisitor[Path] {

  import ScanVisitor._

  /** Stores the encountered files. */
  private val fileBuffer = ListBuffer.empty[FileData]

  /** Stores the encountered directories. */
  private val dirBuffer = ListBuffer.empty[Path]

  /**
   * Returns a sequence with the found files.
   * @return a sequence with all encountered files
   */
  def files: Seq[FileData] = fileBuffer.toList

  /**
   * Returns a sequence with the found directories.
   * @return a squence with all encountered directories
   */
  def directories: Seq[Path] = dirBuffer.toList

  /**
   * @inheritdoc This implementation stores data about the encountered file.
   */
  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    if (!exclusions.contains(extractExtension(file).toUpperCase(Locale.ENGLISH))) {
      fileBuffer += createFileData(file)
    }
    FileVisitResult.CONTINUE
  }

  /**
   * @inheritdoc This implementation records the current directory.
   */
  override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
    dirBuffer += dir
    FileVisitResult.CONTINUE
  }

  /**
   * Creates a ''FileData'' object for the specified path.
   * @param file the path to the file
   * @return the corresponding ''FileData''
   */
  private def createFileData(file: Path): FileData = FileData(file, Files size file)
}
