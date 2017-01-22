/*
 * Copyright 2015-2017 The Developers Team.
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

package de.oliver_heger.linedj.archiveunion

import java.nio.file.Path

import de.oliver_heger.linedj.shared.archive.media.MediumID
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetaData

/**
  * A message with the result of meta data extraction for a single media file.
  *
  * Messages of this type are sent to the meta data manager actor whenever a
  * media file has been processed. The message contains the meta data that
  * could be extracted.
  *
  * @param path the path to the media file
  * @param mediumID the ID of the medium this file belongs to
  * @param uri the URI of the file
  * @param metaData an object with the meta data that could be extracted
  */
case class MetaDataProcessingResult(path: Path, mediumID: MediumID, uri: String,
                                    metaData: MediaMetaData)
