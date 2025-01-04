/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.temp

import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

object TempPathGenerator:
  /** A prefix used for download files and directories. */
  val DownloadPrefix = "download-"

/**
  * A helper class that generates path names for temporary files that are
  * needed during download operations.
  *
  * This class generates path names that follow a specific pattern to ensure
  * that multiple download operations for multiple HTTP archive instances can
  * take place in parallel on the same directory for temporary files. To
  * achieve this, each archive has a sub directory with its name and a
  * timestamp reflecting the startup time. This is done to avoid conflicts
  * with remaining temporary files from earlier runs; those files can be
  * removed on startup, but in parallel new temporary files may need to be
  * created.
  *
  * For each download operation multiple temporary files may have to be
  * created. Therefore, a path names contains the sequence number for the
  * download operation and an index for the temporary file in this operation.
  * The paths are located in the sub directory of the owning archive.
  *
  * @param rootPath the root path for temporary files; all generated paths are
  *                 children of this root path
  * @param time     the startup time of the current run (this parameter is
  *                 normally not specified when creating an instance)
  */
case class TempPathGenerator(rootPath: Path, time: Instant = Instant.now()):

  import TempPathGenerator._

  /**
    * A component for generated paths based on the time. So for each run of the
    * archive, different paths are going to be generated.
    */
  private val timeComponent = DateTimeFormatter.ISO_INSTANT.format(time).replace(':', '_')

  /**
    * Generates the path to the sub directory with temporary files for the
    * specified HTTP archive. The archive name is incorporated in the
    * generated path name; therefore, it should not contain any special
    * characters.
    *
    * @param archiveName the name of the HTTP archive
    * @return the sub path for temp files for this archive
    */
  def generateArchivePath(archiveName: String): Path =
    rootPath resolve s"$DownloadPrefix$archiveName-$timeComponent"

  /**
    * Generates the path for a temporary file for a download operation.
    *
    * @param archiveName the name of the HTTP archive
    * @param downloadIdx the index of the download operation
    * @param fileIdx     the index of the temporary file in the operation
    * @return the path for this temporary file
    */
  def generateDownloadPath(archiveName: String, downloadIdx: Int, fileIdx: Int): Path =
    generateArchivePath(archiveName).resolve(s"$DownloadPrefix${downloadIdx}_$fileIdx.tmp")

  /**
    * Checks whether the specified path points to a temporary download file
    * that can be removed. This method is called when cleaning up the root
    * directory for temporary download files. Only the paths accepted by the
    * method are actually removed. This implementation checks whether the
    * path has the prefix for download files and has not been generated for
    * the current run.
    *
    * @param p the path to be checked
    * @return a flag whether this path is a candidate to be removed
    */
  def isRemovableTempPath(p: Path): Boolean =
    !p.toString.contains(timeComponent) && p.getFileName.toString.contains(DownloadPrefix)
