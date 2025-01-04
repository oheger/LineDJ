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

import de.oliver_heger.linedj.archivecommon.parser.MetadataParser
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import spray.json.*

/**
  * A converter class for transforming media metadata to JSON strings.
  *
  * This converter is used when writing media metadata to disk. The generated
  * files use JSON syntax. The metadata for each song is represented by a
  * JSON object. This converter generates the JSON representation for a single
  * song.
  */
class MetadataJsonConverter:
  /**
    * Generates a JSON representation for the given metadata.
    *
    * @param uri  the URI of the associated file
    * @param data the metadata for this file
    * @return a string with the JSON representation of this data
    */
  def convert(uri: String, data: MediaMetadata): String =
    val model = MetadataParser.MetadataWithUri(uri, data)
    model.toJson.prettyPrint
