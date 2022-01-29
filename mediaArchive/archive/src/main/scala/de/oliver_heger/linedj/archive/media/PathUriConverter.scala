/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.archive.media

import com.github.cloudfiles.core.http.UriEncodingHelper
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri

import java.net.URI
import java.nio.file.Path

/**
  * A helper class that is responsible for converting between paths and URIs in
  * archives based on local directory structures.
  *
  * This class implements a straight-forward mapping between paths and media
  * file URIs: An URI is constructed from the relative file path (to the root
  * path of the archive) and applying URI-encoding. So such URIs are
  * independent on a concrete directory layout, and can be interpreted in the
  * context of another archive as well.
  *
  * @param rootPath the root path of the archive
  */
class PathUriConverter(val rootPath: Path) {
  /** A URI representing the archive's root path. */
  val rootUri: URI = pathToUriEncoded(rootPath)

  /**
    * Generates a URI for the given path of a media file. The path is expected
    * to be sub path of the archive root path.
    *
    * @param path the path to the media file
    * @return the corresponding ''MediaFileUri''
    */
  def pathToUri(path: Path): MediaFileUri =
    MediaFileUri(rootUri.relativize(pathToUriEncoded(path)).getPath)

  /**
    * Returns the path for the media file with the given URI. The URI is
    * expected to be relative to the root URI of the archive.
    *
    * @param uri the URI of the media file
    * @return the corresponding path
    */
  def uriToPath(uri: MediaFileUri): Path =
    rootPath.resolve(UriEncodingHelper.removeLeadingSeparator(UriEncodingHelper.decodeComponents(uri.uri)))

  /**
    * Converts the given path to a URI with encoding. Unfortunately, the
    * ''toURri()'' method of ''Path'' does not do any encoding; so this has to
    * be done manually.
    *
    * @param path the path
    * @return the corresponding encoded URI
    */
  private def pathToUriEncoded(path: Path): URI =
    new URI(UriEncodingHelper.encodeComponents(path.toUri.toString))
}
