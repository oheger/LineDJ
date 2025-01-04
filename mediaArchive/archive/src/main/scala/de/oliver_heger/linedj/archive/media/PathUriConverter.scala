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

package de.oliver_heger.linedj.archive.media

import com.github.cloudfiles.core.http.UriEncodingHelper
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri

import java.net.URI
import java.nio.file.Path

object PathUriConverter:
  /**
    * Generates a ''MediaFileUri'' for the given path that is relative to the
    * provided root URI. It is expected that the root URI points to a parent
    * directory of the given path.
    *
    * @param rootUri the root URI for generating relative URIs
    * @param path    the path to convert
    * @return a ''MediaFileUri'' for the path relative to the root URI
    */
  def pathToRelativeUri(rootUri: URI, path: Path): MediaFileUri =
    MediaFileUri(rootUri.relativize(pathToURI(path)).getPath)

  /**
    * Converts the given path to a ''URI''. This is typically necessary as an
    * intermediate step before constructing a relative [[MediaFileUri]]. If
    * multiple conversions from paths to [[MediaFileUri]]s are needed for the
    * same root path, it is more efficient to convert the root path to a URI
    * first and then reuse it for later conversions. Note: Unfortunately, the
    * ''toURri()'' method of ''Path'' does not do any encoding; so this has to
    * be done manually.
    *
    * @param path the path
    * @return the corresponding encoded URI
    */
  def pathToURI(path: Path): URI =
    new URI(UriEncodingHelper.encodeComponents(path.toUri.toString))

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
class PathUriConverter(val rootPath: Path):

  import de.oliver_heger.linedj.archive.media.PathUriConverter._

  /** A URI representing the archive's root path. */
  val rootUri: URI = pathToURI(rootPath)

  /**
    * Generates a URI for the given path of a media file. The path is expected
    * to be sub path of the archive root path.
    *
    * @param path the path to the media file
    * @return the corresponding ''MediaFileUri''
    */
  def pathToUri(path: Path): MediaFileUri = pathToRelativeUri(rootUri, path)

  /**
    * Returns the path for the media file with the given URI. The URI is
    * expected to be relative to the root URI of the archive.
    *
    * @param uri the URI of the media file
    * @return the corresponding path
    */
  def uriToPath(uri: MediaFileUri): Path =
    rootPath.resolve(UriEncodingHelper.removeLeadingSeparator(UriEncodingHelper.decodeComponents(uri.uri)))
