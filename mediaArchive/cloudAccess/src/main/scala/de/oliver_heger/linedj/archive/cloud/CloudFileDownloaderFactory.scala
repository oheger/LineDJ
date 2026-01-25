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

package de.oliver_heger.linedj.archive.cloud

import scala.concurrent.Future

/**
  * A trait defining a factory interface for creating a [[CloudFileDownloader]] for
  * a specific cloud archive.
  *
  * A concrete implementation has to construct such a downloader object based
  * on a [[CloudArchiveConfig]] object.
  */
trait CloudFileDownloaderFactory:
  /**
    * Creates a fully initialized [[CloudFileDownloader]] that can download
    * files from the archive with the given configuration. An implementation
    * has to evaluate all relevant attributes of the configuration to create a
    * suitable downloader object. This includes the cloud file system to access
    * the archive and the authentication method. Obtaining all the required
    * information can involve asynchronous steps that may also fail. Therefore,
    * this function returns a [[Future]].
    *
    * @param config the configuration of the archive the downloader is for
    * @return a [[Future]] with the constructed [[CloudFileDownloader]]
    */
  def createDownloader(config: CloudArchiveConfig): Future[CloudFileDownloader]
