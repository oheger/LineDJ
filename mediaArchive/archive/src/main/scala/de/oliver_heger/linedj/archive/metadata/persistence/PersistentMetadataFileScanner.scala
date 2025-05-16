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

package de.oliver_heger.linedj.archive.metadata.persistence

import com.github.cloudfiles.core.Model
import com.github.cloudfiles.core.utils.Walk
import de.oliver_heger.linedj.io.LocalFsUtils
import de.oliver_heger.linedj.shared.archive.metadata.MediumChecksum
import org.apache.pekko.actor.{ActorSystem, typed}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.Sink

import java.nio.file.Path
import scala.concurrent.Future

object PersistentMetadataFileScanner:
  /** The file extension for persistent metadata files. */
  final val MetadataFileExtension = "mdt"

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
    * Filters the given list of elements for files with metadata about media.
    *
    * @param elements the elements
    * @return the filtered elements
    */
  private def filterMetadataFiles(elements: List[Model.Element[Path]]): List[Model.Element[Path]] =
    elements.filter {
      case f: Model.File[Path] if LocalFsUtils.extractExtension(f.id) == MetadataFileExtension => true
      case _ => false
    }
end PersistentMetadataFileScanner

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

  import PersistentMetadataFileScanner.*

  /**
    * Scans the specified directory for metadata files. All detected files are
    * returned in a map. The key of the map is the checksum of a file; the full
    * path is provided as value. Scanning is done in background; therefore,
    * result is a future. If an ''IOException'' occurs (which typically
    * means that the directory does not exist), result is a failed future.
    *
    * @param dir                    the directory to be scanned
    * @param blockingDispatcherName the name of the blocking dispatcher
    * @param system                 the actor system
    * @return a future with a map with the results of the scan operation
    */
  def scanForMetadataFiles(dir: Path, blockingDispatcherName: String)
                          (implicit system: ActorSystem): Future[Map[MediumChecksum, Path]] =
    val localFs = LocalFsUtils.createLocalFs(dir, blockingDispatcherName, system)
    val walkConfig = Walk.WalkConfig(
      fileSystem = localFs,
      httpActor = null,
      rootID = dir,
      transform = filterMetadataFiles
    )

    given typed.ActorSystem[_] = system.toTyped

    val source = Walk.dfsSource(walkConfig)
      .filter {
        case _: Model.File[Path] => true
        case _ => false
      }
      .map(elem => (checksumFor(elem.id), elem.id))
    val sink = Sink.fold[Map[MediumChecksum, Path], (MediumChecksum, Path)](Map.empty)(_ + _)
    source runWith sink
