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

package de.oliver_heger.linedj.archive.metadata.persistence

import java.io.IOException
import java.nio.file.{Files, Path}

import org.slf4j.LoggerFactory

object PersistentMetaDataFileScanner {
  /** The file extension for persistent meta data files. */
  val MetaDataFileExtension = "mdt"

  /** The glob for selecting meta data files. */
  private val MetaDataFileGlob = "*." + MetaDataFileExtension
}

/**
  * An internally used helper class for scanning for files with persistent
  * meta data.
  *
  * Media data is stored in files in a specific directory. This class defines
  * a method for iterating over all files in this directory. As a naming
  * convention, meta data files are named by the checksum of the medium they
  * represent and have the extension ''.mdt''. This class extracts the
  * checksum from the file name and returns a map with checksums as keys and
  * corresponding paths as values.
  */
private class PersistentMetaDataFileScanner {

  import PersistentMetaDataFileScanner._

  /** The logger. */
  val log = LoggerFactory.getLogger(getClass)

  /**
    * Scans the specified directory for meta data files. All detected files are
    * returned in a map. The key of the map is the checksum of a file; the full
    * path is provided as value. If an ''IOException'' occurs (which typically
    * means that the directory does not exist), result is an empty map.
    *
    * @param dir the directory to be scanned
    * @return a map with the results of the scan operation
    */
  def scanForMetaDataFiles(dir: Path): Map[String, Path] = {
    try {
      log.info("Scanning directory {} for mdt files.", dir)
      val stream = Files.newDirectoryStream(dir, MetaDataFileGlob)

      try {
        import scala.collection.JavaConverters._
        val iterator = stream.iterator().asScala
        iterator.foldLeft(Map.empty[String, Path]) { (m, p) =>
          val name = p.getFileName.toString
          val checkSum = name.substring(0, name.lastIndexOf('.'))
          m + (checkSum -> p)
        }
      } finally {
        try {
          stream.close()
        } catch {
          case e: IOException =>
            log.warn("Exception when closing directory stream.", e)
        }
      }
    } catch {
      case e: IOException =>
        log.error("Could not read meta data directory: " + dir, e)
        Map.empty[String, Path]
    }
  }
}
