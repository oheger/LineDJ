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

package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.media.EnhancedMediaScanResult
import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.MediumID

/**
  * A message defining the files of a medium for which no persistent metadata
  * could be retrieved. A message of this type is sent by the persistent
  * metadata manager after the available metadata for a medium has been read.
  * The files listed here could not be resolved; their metadata needs to be
  * extracted manually.
  *
  * @param mediumID the ID of the medium the files belong to
  * @param files    a list with the unresolved media files
  * @param result   the original scan result these files belong to
  */
case class UnresolvedMetadataFiles(mediumID: MediumID, files: List[FileData],
                                   result: EnhancedMediaScanResult)

/**
  * A message processed by the persistence manager actor which
  * triggers the scan of the configured directory for metadata files. This
  * message must be sent to the actor at least once initially. To be sure
  * that the actor operates on up-to-date metadata files, the message
  * should be sent again before every new file scan starts.
  */
case object ScanForMetadataFiles
