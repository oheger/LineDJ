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

package de.oliver_heger.linedj.extract.metadata

import org.apache.pekko.actor.{ActorRef, Props}

/**
  * Factory interface for creating actors to extract metadata from specific
  * media files.
  *
  * This trait is used by actors responsible for metadata extraction. Such
  * actors process a list of media files. Based on the file type, different
  * methods for metadata extraction might be required. This factory trait
  * introduces a mechanism to create concrete extractor actors. It assumes that
  * the media file's extension determines the extractor to be used. If a file
  * extension is not supported, an implementation can return ''None''; in
  * this case only dummy metadata is generated.
  */
trait ExtractorActorFactory:
  /**
    * Returns an option with a ''Props'' object to create an actor that can
    * extract metadata from a file with the given extension. This method is
    * called during media file processing with the file extensions of
    * encountered media files (without the dot delimiter). If the extension is
    * supported, an implementation must return a ''Props'' object for an actor
    * that is able to process a
    * [[de.oliver_heger.linedj.shared.archive.union.ProcessMetadataFile]]
    * message and send extracted metadata to the provided receiver actor.
    *
    * @param extension the file extension
    * @param receiver  the target actor to receive extracted metadata
    * @return optional ''Props'' to create the extractor actor
    */
  def extractorProps(extension: String, receiver: ActorRef): Option[Props]
