/*
 * Copyright 2015-2024 The Developers Team.
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

import de.oliver_heger.linedj.archive.media.MediumChecksum
import de.oliver_heger.linedj.io.DirectoryStreamSource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

object PersistentMetadataFileScanner:
  /** The file extension for persistent metadata files. */
  final val MetadataFileExtension = "MDT"

  /**
    * Determines the checksum of a metadata file.
    *
    * @param p the path of the metadata file
    * @return the checksum for this file
    */
  private def checksumFor(p: Path): MediumChecksum =
    val name = p.getFileName.toString
    MediumChecksum(name.substring(0, name.lastIndexOf('.')))

  /**
    * The transformation function used by the directory source. Each metadata
    * file is converted to a tuple consisting of the checksum and the path of a
    * metadata file. Note that the ''dir'' flag is ignored because no sub
    * directories are passed.
    *
    * @param p   the path to a metadata file
    * @param dir the flag whether the path is a directory
    * @return the transformed stream element
    */
  private def transformMetadataFile(p: Path, dir: Boolean): (MediumChecksum, Path) =
    (checksumFor(p), p)

/**
  * An internally used helper class for scanning for files with persistent
  * metadata.
  *
  * Media data is stored in files in a specific directory. This class defines
  * a method for iterating over all files in this directory. As a naming
  * convention, metadata files are named by the checksum of the medium they
  * represent and have the extension ''.mdt''. This class extracts the
  * checksum from the file name and returns a map with checksums as keys and
  * corresponding paths as values.
  */
private class PersistentMetadataFileScanner:

  import PersistentMetadataFileScanner._

  /**
    * Scans the specified directory for metadata files. All detected files are
    * returned in a map. The key of the map is the checksum of a file; the full
    * path is provided as value. Scanning is done in background; therefore,
    * result is a future. If an ''IOException'' occurs (which typically
    * means that the directory does not exist), result is a failed future.
    *
    * @param dir the directory to be scanned
    * @param ec the execution context
    * @return a future with a map with the results of the scan operation
    */
  def scanForMetadataFiles(dir: Path)(implicit system: ActorSystem, ec: ExecutionContext):
  Future[Map[MediumChecksum, Path]] =
    val source = DirectoryStreamSource.newBFSSource(dir,
      pathFilter = DirectoryStreamSource
        .includeExtensionsFilter(Set(MetadataFileExtension)))(transformMetadataFile)
    val sink = Sink.fold[Map[MediumChecksum, Path], (MediumChecksum, Path)](Map.empty)(_ + _)
    source runWith sink
