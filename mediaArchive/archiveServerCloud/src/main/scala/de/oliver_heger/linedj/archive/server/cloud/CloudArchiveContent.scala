/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.shared.archive.metadata.Checksums

/**
  * A data class representing a medium that is stored in a cloud archive. For
  * this application, only limited information is relevant that allows to
  * decide whether this medium is already available in a local cache.
  *
  * @param id        the ID of the medium
  * @param timestamp the timestamp when the medium was changed remotely
  */
case class MediumEntry(id: Checksums.MediumChecksum,
                       timestamp: Long)

/**
  * A data class storing the content of a cloud archive. This mainly means the
  * media available in this archive.
  *
  * @param media a map with information about the media located in the archive
  */
case class CloudArchiveContent(media: Map[Checksums.MediumChecksum, MediumEntry])
