/*
 * Copyright 2015-2024 The Developers Team.
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
import de.oliver_heger.linedj.extract.id3.processor.Mp3MetaDataExtractorActor
import de.oliver_heger.linedj.extract.metadata.ExtractorActorFactory
import org.apache.pekko.actor.{ActorRef, Props}

import java.util.Locale

/**
  * A default implementation of the [[ExtractorActorFactory]] trait.
  *
  * This implementation maps file extensions to extractor actors for the
  * supported media file formats. An instance is passed to the media extraction
  * actors, so that they can delegate concrete extraction tasks to
  * specialized actors.
  *
  * @param config the configuration of the media archive
  */
class ExtractorActorFactoryImpl(val config: MediaArchiveConfig) extends ExtractorActorFactory:
  /**
    * @inheritdoc This implementation supports some special file extensions
    *             (case is irrelevant), for which corresponding ''Props''
    *             objects are returned. All other file extensions yield a
    *             ''None'' answer.
    */
  override def extractorProps(extension: String, receiver: ActorRef): Option[Props] =
    extension.toLowerCase(Locale.ENGLISH) match
      case "mp3" =>
        Some(Mp3MetaDataExtractorActor(receiver, config.tagSizeLimit,
          config.metaDataReadChunkSize))

      case _ => None
