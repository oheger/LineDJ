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

package de.oliver_heger.linedj.archivehttp.config

import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archive.cloud.CloudFileDownloader
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout

import java.nio.file.Path
import scala.concurrent.duration._

/**
  * A data class representing user credentials.
  *
  * This class holds credential information that is needed to connect to an
  * HTTP archive.
  *
  * @param userName the user name
  * @param password the password as a ''Secret''
  */
case class UserCredentials(userName: String, password: Secret)

/**
  * A class defining the configuration settings to be applied for an HTTP
  * archive.
  *
  * @param archiveBaseUri        the base URI of the HTTP media archive; all
  *                              paths are resolved against this URI
  * @param archiveName           a name for the HTTP media archive
  * @param contentPath           the (relative) path to the content file
  * @param mediaPath             the (relative) base path for all media files
  * @param metadataPath          the (relative) path under which all metadata
  *                              files are located
  * @param processorCount        the number of parallel processor actors to be used
  *                              when downloading metadata from the archive
  * @param processorTimeout      the timeout for calls to processor actors
  * @param propagationBufSize    number of processed media whose content can be
  *                              propagated to the union archive in parallel
  * @param maxContentSize        the maximum size of a content file (either a
  *                              settings or a metadata file) in kilobytes; if a
  *                              file is larger, it is canceled
  * @param downloadBufferSize    the size of the in-memory buffer for download
  *                              operations
  * @param downloadMaxInactivity definition of an inactivity span for download
  *                              operations; if no data is requested by the
  *                              client in this interval, a data chunk must be
  *                              requested from the HTTP archive
  * @param downloadReadChunkSize a chunk size when reading data from a temporary
  *                              file created during a download operation
  * @param timeoutReadSize       the size of data to read from the source to avoid
  *                              a timeout
  * @param downloadConfig        configuration for standard download properties
  * @param downloader            the object to download media files
  */
case class HttpArchiveConfig(archiveBaseUri: Uri,
                             archiveName: String,
                             contentPath: Uri.Path,
                             mediaPath: Uri.Path,
                             metadataPath: Uri.Path,
                             processorCount: Int,
                             processorTimeout: Timeout,
                             propagationBufSize: Int,
                             maxContentSize: Int,
                             downloadBufferSize: Int,
                             downloadMaxInactivity: FiniteDuration,
                             downloadReadChunkSize: Int,
                             timeoutReadSize: Int,
                             downloadConfig: DownloadConfig,
                             downloader: CloudFileDownloader)
