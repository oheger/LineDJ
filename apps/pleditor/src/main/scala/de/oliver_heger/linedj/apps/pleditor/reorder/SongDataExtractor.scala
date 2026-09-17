/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.apps.pleditor.reorder

import de.oliver_heger.linedj.apps.pleditor.SongData
import de.oliver_heger.linedj.reorder.spi.MetadataExtractor

/**
  * A [[MetadataExtractor]] implementation that operates on [[SongData]]
  * instances.
  *
  * This extractor provides access to the metadata properties of the concrete
  * song data type used by the playlist editor. In this way, the generic
  * reorder components are made available to this application.
  *
  * The _title_, _artist_, and _album_ properties are returned as _None_ if
  * they are undefined (i.e. empty strings) for a song.
  */
object SongDataExtractor extends MetadataExtractor[SongData]:

  override def title(data: SongData): Option[String] =
    data.optFileInfo.flatMap(_.metadata.title).filter(_.nonEmpty)

  override def artist(data: SongData): Option[String] =
    data.optFileInfo.flatMap(_.metadata.artist).filter(_.nonEmpty)

  override def album(data: SongData): Option[String] =
    data.optFileInfo.flatMap(_.metadata.album).filter(_.nonEmpty)

  override def inceptionYear(data: SongData): Option[Int] =
    data.optFileInfo.flatMap(_.metadata.inceptionYear)

  override def trackNumber(data: SongData): Option[Int] =
    data.optFileInfo.flatMap(_.metadata.trackNumber)

  override def uri(data: SongData): String =
    data.optFileInfo.map(_.fileUri.uri).getOrElse("")
