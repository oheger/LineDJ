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

import de.oliver_heger.linedj.io.FileData

/**
 * A data class storing the results of a directory scan for media files (in its
 * raw form).
 *
 * This class consists of a map with ''MediumID'' objects and the files of this
 * medium assigned to it. The medium ID can be used to obtain a path pointing
 * to the corresponding medium description file if available.
 *
 * @param root the root path that has been scanned
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
 * @param scanResult the original ''MediaScanResult''
 * @param checksumMapping a map storing checksums for the contained media
 * @param fileUriMapping a mapping from file URIs to the file objects
 */
case class EnhancedMediaScanResult(scanResult: MediaScanResult,
                                   checksumMapping: Map[MediumID, String],
                                   fileUriMapping: Map[String, FileData])
