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

package de.oliver_heger.linedj.archive.media

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.Checksums.MediumChecksum
import org.apache.pekko.actor.ActorRef

import java.nio.file.Path

/**
  * A data class storing the results of a directory scan for media files (in its
  * raw form).
  *
  * This class consists of a map with ''MediumID'' objects and the files of this
  * medium assigned to it. The medium ID can be used to obtain a path pointing
  * to the corresponding medium description file if available.
  *
  * @param root       the root path that has been scanned
  * @param mediaFiles a map with files assigned to a medium
  */
case class MediaScanResult(root: Path, mediaFiles: Map[MediumID, List[FileData]])

/**
  * A data class storing the result of a directory scan plus some additional
  * information which can be useful for further processing.
  *
  * The ''MediaManagerActor'' first operates on [[MediaScanResult]] object, but
  * obtains some additional information about the contained media during
  * processing. This case class combines the original scan result with this
  * additional information.
  *
  * @param scanResult      the original ''MediaScanResult''
  * @param checksumMapping a map storing checksums for the contained media
  */
case class EnhancedMediaScanResult(scanResult: MediaScanResult,
                                   checksumMapping: Map[MediumID, MediumChecksum])

/**
  * A message that serves as an indicator about a newly started media scan.
  *
  * This message is sent by the media manager actor to the metadata manager
  * actor, so that it can reset its state and prepare itself to receive media
  * scan results.
  *
  * @param client the client of the current scan operation
  */
case class MediaScanStarts(client: ActorRef)
