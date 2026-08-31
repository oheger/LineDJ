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

package de.oliver_heger.linedj.platform.audio2.playlist

import de.oliver_heger.linedj.archive.server.model.ArchiveModel

import scala.util.Try

/**
  * A trait defining the interface of a service for resolving IDs of media
  * files.
  *
  * The service can be passed a number of file IDs. It queries a media archive
  * for the corresponding [[ArchiveModel.MediaFileInfo]] objects and invokes a
  * callback function with the results in the UI thread.
  */
trait MediaFileInfoResolverService:
  /**
    * Resolves the given IDs of media files. When this is done, the function 
    * passes the results to a provided callback. Since this function is 
    * intended to be used by UI components, the callback is guaranteed to be 
    * called in the UI thread.
    *
    * @param ids      the IDs of media files to resolve
    * @param callback the callback to invoke with the result
    */
  def resolveFileIDs(ids: Iterable[String])(callback: Try[Map[String, ArchiveModel.MediaFileInfo]] => Unit): Unit
