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

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.util.Locale

import de.oliver_heger.linedj.io.FileData

import scala.collection.mutable.ListBuffer

object MediaScanner {
  /** The file separator character. */
  private val FileSeparator = System.getProperty("file.separator")

  /**
   * Determines the path prefix of a medium description file. This is the
   * directory which contains the description file as string.
   * @param descFile the path to the description file
   * @return the prefix for this description file
   */
  private def descriptionPrefix(descFile: Path): String =
    descFile.getParent.toString
}

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
private class MediaScanner(val excludedExtensions: Set[String]) {
  import MediaScanner._

  /**
   * Scans a given directory structure.
   * @param root the root of the directory structure to be scanned
   * @return the result of the scan operation
   */
  def scan(root: Path): MediaScanResult = {
    val visitor = new ScanVisitor(excludedExtensions)
    Files.walkFileTree(root, visitor)
    MediaScanResult(root, createResultMap(visitor.mediumDescriptions, visitor.mediaFiles, root
      .toString))
  }

  /**
   * Creates the map with result data for a scan operation. As the scanner only
   * returns a list with all media files and a list with all medium description
   * files, a transformation has to take place in order to create the map with
   * all results.
   * @param mediumDescriptions the list with the medium description files
   * @param mediaFiles the list with all files
   * @param mediumURI the URI for the medium
   * @return the result map
   */
  private def createResultMap(mediumDescriptions: List[Path], mediaFiles: List[FileData],
                              mediumURI: String): Map[MediumID, List[FileData]] = {
    val sortedDescriptions = mediumDescriptions sortWith (descriptionPrefix(_) >
      descriptionPrefix(_))
    val start = (mediaFiles, Map.empty[MediumID, List[FileData]])
    val end = sortedDescriptions.foldLeft(start) { (state, path) =>
      val partition = findFilesForDescription(path, state._1)
      (partition._2, state._2 + (MediumID.fromDescriptionPath(path) -> partition._1))
    }

    if (end._1.isEmpty) end._2
    else {
      end._2 + (MediumID(mediumURI, None) -> end._1)
    }
  }

  /**
   * Finds all files which belong to the given medium description file. All
   * such files are contained in the first list of the returned tuple. The
   * second list contains the remaining files.
   * @param desc the path to the description file
   * @param mediaFiles the list with all media files
   * @return a partition with the files for this description and the remaining
   *         files
   */
  private def findFilesForDescription(desc: Path, mediaFiles: List[FileData]): (List[FileData],
    List[FileData]) = {
    val prefix = descriptionPrefix(desc)
    val len = prefix.length
    mediaFiles partition (f => belongsToMedium(prefix, len, f.path))
  }

  /**
   * Checks whether the specified path belongs to a specific medium. The prefix
   * URI for this medium is specified. This method checks whether the path
   * starts with this prefix, but is a real sub directory. (Files in the same
   * directory in which the medium description file is located are not
   * considered to belong to this medium.)
   * @param prefix the prefix for the medium
   * @param prefixLen the length of the prefix
   * @param path the path to be checked
   * @return a flag whether this path belongs to this medium
   */
  private def belongsToMedium(prefix: String, prefixLen: Int, path: Path): Boolean = {
    val pathStr = path.toString
    pathStr.startsWith(prefix) && pathStr.lastIndexOf(FileSeparator) > prefixLen
  }

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
 * files. An instance is used internally by [[MediaScanner]].
 *
 * @param exclusions a set with file extensions to be ignored
 */
private class ScanVisitor(exclusions: Set[String]) extends SimpleFileVisitor[Path] {

  import de.oliver_heger.linedj.media.ScanVisitor._

  /** A list with all media files encountered during the scan operation. */
  private val mediaFileList = ListBuffer.empty[FileData]

  /** A list with all medium description files encountered during the scan. */
  private val descriptionFileList = ListBuffer.empty[Path]

  /**
   * Returns a list with all detected media files.
   * @return the list with all media files
   */
  def mediaFiles: List[FileData] = mediaFileList.toList

  /**
   * Returns a list with all detected medium description files.
   * @return the list with medium description files
   */
  def mediumDescriptions: List[Path] = descriptionFileList.toList

  override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
    val extension = extractExtension(file)
    if (SettingsExtension == extension) {
      descriptionFileList += file
    } else if (!exclusions.contains(extension.toUpperCase(Locale.ENGLISH))) {
      mediaFileList += createFileData(file)
    }
    FileVisitResult.CONTINUE
  }

  /**
   * Creates a ''FileData'' object for the specified path. This method
   * obtains the additional meta data required by the ''FileData'' class.
   * @param path the path to the file in question
   * @return the corresponding ''FileData'' object
   */
  private def createFileData(path: Path): FileData = FileData(path, Files size path)
}
