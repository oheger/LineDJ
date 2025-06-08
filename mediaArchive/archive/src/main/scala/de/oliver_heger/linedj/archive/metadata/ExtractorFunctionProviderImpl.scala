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

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.extract.id3.stream.ID3ExtractorStream
import de.oliver_heger.linedj.extract.metadata.ExtractorFunctionProvider
import de.oliver_heger.linedj.extract.metadata.ExtractorFunctionProvider.ExtractorFunc

import java.util.Locale

/**
  * A default implementation of the [[ExtractorFunctionProvider]] trait.
  *
  * This implementation maps file extensions to extractor functions for the
  * supported media file formats. An instance is passed to the media extraction
  * actors, so that they can delegate concrete extraction tasks to specialized
  * functions.
  *
  * @param config the configuration of the media archive
  */
class ExtractorFunctionProviderImpl(val config: MediaArchiveConfig) extends ExtractorFunctionProvider:
  override def extractorFuncFor(extension: String): Option[ExtractorFunc] =
    extension.toLowerCase(Locale.ROOT) match
      case "mp3" => Some(ID3ExtractorStream.id3ExtractorFunc(config.tagSizeLimit))
      case _ => None
