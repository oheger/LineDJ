/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import java.nio.file.Path

import de.oliver_heger.linedj.io.{DirectoryScanner, FileData}
import de.oliver_heger.linedj.shared.archive.media.MediumID

object MediaScanner {
  /** The file separator character. */
  private val FileSeparator = System.getProperty("file.separator")

  /** Constant for the extension for medium description files. */
  private val SettingsExtension = ".settings"

  /**
   * Determines the path prefix of a medium description file. This is the
   * directory which contains the description file as string.
   * @param descFile the path to the description file
   * @return the prefix for this description file
   */
  private def descriptionPrefix(descFile: Path): String =
    descFile.getParent.toString

  /**
   * Checks whether the specified file is a medium settings file.
   * @param file the file to be checked
   * @return a flag whether this is a settings file
   */
  private def isSettingsFile(file: FileData): Boolean =
    file.path.toString endsWith SettingsExtension
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
    val scanner = new DirectoryScanner(excludedExtensions)
    val scanResult = scanner scan root
    val (descriptions, files) = scanResult.files partition isSettingsFile
    MediaScanResult(root, createResultMap(descriptions.map(_.path).toList, files.toList, root
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
