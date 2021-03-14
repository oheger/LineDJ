/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.Future

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
    * passed in URI is resolved and accessed asynchronously. If this is
    * successful, a ''Source'' with the content of the file is returned, which
    * can then be consumed by the caller. Implementations are responsible of
    * cleaning up all resources in case of failure.
    *
    * @param uri the URI of the file to be resolved
    * @return a ''Future'' with a ''Source'' of the file's content
    */
  def downloadMediaFile(uri: Uri): Future[Source[ByteString, Any]]
}
