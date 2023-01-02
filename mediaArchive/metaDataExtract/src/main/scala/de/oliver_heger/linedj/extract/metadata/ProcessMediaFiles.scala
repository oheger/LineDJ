/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.extract.metadata

import de.oliver_heger.linedj.io.FileData
import de.oliver_heger.linedj.shared.archive.media.{MediaFileUri, MediumID}

import java.nio.file.Path

/**
  * A message received by [[MetaDataExtractionActor]] telling it to process the
  * specified media files.
  *
  * This message actually initiates the extraction of meta data by the receiving
  * actor instance. It contains all files of a medium for which no persistent
  * meta data could be obtained. In addition, a mapping function is provided to
  * generate URIs for the paths of the processed ''FileData'' objects.
  *
  * @param mediumID       the ID of the medium
  * @param files          the files to be processed
  * @param uriMappingFunc a function to obtain URIs for media file paths
  */
case class ProcessMediaFiles(mediumID: MediumID, files: List[FileData],
                             uriMappingFunc: Path => MediaFileUri)
