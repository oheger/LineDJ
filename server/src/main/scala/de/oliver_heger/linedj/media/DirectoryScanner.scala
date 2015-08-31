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

package de.oliver_heger.linedj.media

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.util.Locale

import scala.collection.mutable.ListBuffer

/**
 * An internally used helper class for scanning a directory structure for media
 * files and directories.
 *
 * An instance of this class is configured with a root directory to be scanned
 * and a set of file extensions to be excluded. It scans the whole directory
 * structure collecting all media files encountered. When a file with a medium
 * description is found the current directory is considered the root directory
 * of a medium. All files in sub directories are grouped to this medium.
 *
 * The result of the processing of a root directory is an object describing all
 * detected media with their associated files. This is basically a map
 * assigning a ''Path'' to a medium description file to a list of ''Paths''
 * pointing to the corresponding media files. In addition, there is another
 * list of paths for all the files that could not be associated with a medium
 * (because they were encountered in top-level directories not below a medium
 * description file).
 *
 * @param excludedExtensions a set with file extensions to be excluded
 */
private class DirectoryScanner(val excludedExtensions: Set[String]) {

  /**
   * Scans a given directory structure.
   * @param root the root of the directory structure to be scanned
   * @return the result of the scan operation
   */
  def scan(root: Path): MediaScanResult = {
    val visitor = new ScanVisitor(excludedExtensions)
    Files.walkFileTree(root, visitor)
    MediaScanResult(root, visitor.mediaFiles filter filterUndefinedMedium)
  }

  /**
   * Filters on the undefined medium ID. The resulting map should include this
   * element only if it actually contains files.
   * @param e the map entry to be filtered
   * @return filter result for this entry
   */
  private def filterUndefinedMedium(e: (MediumID, List[MediaFile])): Boolean =
    e._1 != UndefinedMediumID || e._2.nonEmpty
}

private object ScanVisitor {
  /** Constant for an undefined file extension. */
  private val NoExtension = ""

  /** Constant for the extension for medium description files. */
  private val SettingsExtension = "settings"

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
 * A ''FileVisitor'' implementation which scans a directory tree for media
 * files. An instance is used internally by [[DirectoryScanner]].
 *
 * @param exclusions a set with file extensions to be ignored
 */
private class ScanVisitor(exclusions: Set[String]) extends SimpleFileVisitor[Path] {

  import de.oliver_heger.linedj.media.ScanVisitor._

  /** A stack structure with the list buffers for the current directories. */
  var filesStack = List(ListBuffer.empty[MediaFile])

  /** The buffer for populating the current medium in the next directory. */
  var nextBuffer = filesStack.head

  /** The map storing the files for all media. */
  var mediaFilesMap: Map[MediumID, ListBuffer[MediaFile]] = Map(UndefinedMediumID -> nextBuffer)

  /**
   * Creates the map with media files based on the data collected during the
   * visit operation.
   * @return the map with media files
   */
  def mediaFiles: Map[MediumID, List[MediaFile]] = mediaFilesMap map (e => (e._1, e._2.toList))

  /**
   * Creates the list with files that do not belong to a specific medium based
   * on the information collected during the visit operation.
   * @return the list with other files
   */
  def otherFiles: List[MediaFile] = filesStack.head.toList

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val extension = extractExtension(file)
    if (SettingsExtension == extension) {
      val buffer = ListBuffer.empty[MediaFile]
      mediaFilesMap = mediaFilesMap + (DefinedMediumID(file) -> buffer)
      nextBuffer = buffer

    } else if (!exclusions.contains(extension.toUpperCase(Locale.ENGLISH))) {
      filesStack.head += createMediaFile(file)
    }
    FileVisitResult.CONTINUE
  }

  override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
    filesStack = nextBuffer :: filesStack
    FileVisitResult.CONTINUE
  }

  override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
    nextBuffer = filesStack.head
    filesStack = filesStack.tail
    FileVisitResult.CONTINUE
  }

  /**
   * Creates a ''MediaFile'' object for the specified path. This method
   * obtains the additional meta data required by the ''MediaFile'' class.
   * @param path the path to the file in question
   * @return the corresponding ''MediaFile'' object
   */
  private def createMediaFile(path: Path): MediaFile = MediaFile(path, Files size path)
}
