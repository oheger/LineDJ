/*
 * Copyright 2015-2018 The Developers Team.
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

package de.oliver_heger.linedj.shared.archive.media

/**
  * A data class which uniquely identifies a media file.
  *
  * An audio file in the archive is uniquely identified by the medium it
  * belongs to and the (relative) URI within this medium. The medium is
  * defined either by a [[MediumID]] (which depends on a concrete archive
  * component) or a checksum string. The checksum is calculated based on the
  * content of the medium and thus remains the same no matter from which
  * archive component it is served.
  *
  * With an instance of this class a specific file or its meta data can be
  * requested from the media archive.
  *
  * @param mediumID the ID of the medium the desired source belongs to
  * @param uri      the URI of the desired source relative to the medium
  * @param checksum an optional checksum identifying the medium in a way
  *                 independent on a specific archive component
  */
case class MediaFileID(mediumID: MediumID, uri: String, checksum: Option[String] = None)
