/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.io

import de.oliver_heger.linedj.archivehttp.io.MediaDownloader.appendPaths
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

object MediaDownloader {
  /**
    * Safely appends a suffix path to another path of a URI. This function
    * handles the cases that the path might or might not end with a separator,
    * and the suffix might or might not start with a separator.
    *
    * @param path   the original path
    * @param suffix the path to be appended
    * @return the path with the suffix path appended
    */
  private[io] def appendPaths(path: Uri.Path, suffix: Uri.Path): Uri.Path =
    if (path.endsWithSlash && suffix.startsWithSlash) path ++ suffix.tail
    else if (path.endsWithSlash && !suffix.startsWithSlash || !path.endsWithSlash && suffix.startsWithSlash)
      path ++ suffix
    else (path ?/ suffix.head.toString) ++ suffix.tail

  /**
    * Safely appends a string with segments to the path of a URI. This is
    * analogous to the overloaded variant, but the suffix to be appended is
    * specified as a string.
    *
    * @param path      the original path
    * @param suffixStr the path to be appended
    * @return the path with the segment appended
    */
  private[io] def appendPaths(path: Uri.Path, suffixStr: String): Uri.Path =
    appendPaths(path, Uri.Path(suffixStr))
}

/**
  * A trait to abstract downloading of files from an HTTP archive.
  *
  * This trait is used by all actors managing an HTTP archive to load files
  * from the archive. It provides a function that expects the URI to the file
  * to be loaded and returns a ''Future'' with a source of its content.
  */
trait MediaDownloader {
  /**
    * The central function to download files from an HTTP media archive. The
    * passed in ''Path'' is resolved against the archive's root path and
    * accessed asynchronously. If this is successful, a ''Source'' with the
    * content of the file is returned, which can then be consumed by the
    * caller. Implementations are responsible of cleaning up all resources in
    * case of failure.
    *
    * @param path the relative path of the file to be resolved against the base
    *             path of the archive
    * @return a ''Future'' with a ''Source'' of the file's content
    */
  def downloadMediaFile(path: Uri.Path): Future[Source[ByteString, Any]]

  /**
    * An overloaded function to download files from an HTTP media archive that
    * is optimized for relative URIs. This variant expects a path prefix and a
    * relative path segment to be appended to the prefix. This fits well with
    * the configuration of HTTP archives that define a number of root paths for
    * different kinds of files (media, metadata).
    *
    * @param pathPrefix the root path
    * @param filePath   the relative path of the file to download
    * @return a ''Future'' with a ''Source'' of the file's content
    */
  def downloadMediaFile(pathPrefix: Uri.Path, filePath: String): Future[Source[ByteString, Any]] =
    downloadMediaFile(appendPaths(pathPrefix, filePath))

  /**
    * Shuts down this downloader when it is no longer needed. This function
    * should be called at the end of the life-cycle of this object to make sure
    * that all resources in use are properly released.
    */
  def shutdown(): Unit
}
