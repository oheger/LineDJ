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

import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.ActorSystem

import java.nio.file.Path
import scala.concurrent.Future

object ExtractorFunctionProvider:
  /**
    * Type alias for a function that can extract metadata from an audio file.
    * The function expects the path to the file and an [[ActorSystem]] that can
    * be used for asynchronous processing. It yields a [[Future]] with the
    * metadata that was extracted for this file.
    */
  type ExtractorFunc = (Path, ActorSystem) => Future[MediaMetadata]
end ExtractorFunctionProvider

/**
  * A trait allowing to obtain a function that can extract metadata from a
  * specific audio file.
  *
  * The trait offers a factory function that expects a file extension and
  * yields an optional extractor function for this file type. If the file type
  * is supported, the extractor function can then be invoked to obtain the
  * metadata for this file.
  */
trait ExtractorFunctionProvider:
  /**
    * Returns an [[Option]] with an extractor function to extract the metadata
    * for an audio file with the given file extension. The extension is
    * expected to be passed without the leading dot and is evaluated in a
    * case-insensitive manner. A result of ''None'' means that no extractor
    * function for this file type is available.
    *
    * @param extension the file extension
    * @return an [[Option]] with an extractor function for this file type
    */
  def extractorFuncFor(extension: String): Option[ExtractorFunctionProvider.ExtractorFunc]
