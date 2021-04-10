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

package de.oliver_heger.linedj.archivehttp.config

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import com.github.cloudfiles.core.http.Secret
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.archivecommon.uri.UriMappingSpec
import de.oliver_heger.linedj.archivehttp.io.MediaDownloader

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
  * A data class with information required to access the persistent information
  * related to an OAuth identity provider.
  *
  * When loading or storing an OAuth configuration an instance of this class
  * must be provided. It specifies the storage location of the configuration
  * and also determines some other properties like the encryption status.
  *
  * The whole OAuth configuration for a specific identity provider (IDP) is
  * stored in multiple files in a directory. All files belonging to the same
  * configuration share the same base name. Sensitive information like tokens
  * or client secrets are encrypted using the password provided.
  *
  * @param rootDir     the root directory where all files are stored
  * @param baseName    the base name of the files of this configuration
  * @param encPassword the password to encrypt sensitive files
  */
case class OAuthStorageConfig(rootDir: Path,
                              baseName: String,
                              encPassword: Secret) {
  /**
    * Returns a path in the root directory that is derived from the base name
    * with the given suffix. This is useful to locate concrete files related to
    * a specific IDP configuration.
    *
    * @param suffix the suffix to append to the base name
    * @return the path pointing to the file with the given suffix
    */
  def resolveFileName(suffix: String): Path =
    rootDir.resolve(baseName + suffix)
}

/**
  * A configuration class to define the mapping of URIs read from meta data
  * files.
  *
  * The URIs stored in the meta data files read from an HTTP archive are not
  * necessarily in a form that they can be used directly for accessing files
  * from the archive. So a mapping may be required firs. This class combines
  * the properties that make up such a mapping.
  *
  * The properties are all optional; default values are used if they are not
  * specified.
  *
  * @param removePrefix     a prefix to be removed from URIs
  * @param removeComponents the number of prefix path components to remove
  * @param uriTemplate      a template to construct the resulting URI
  * @param pathSeparator    the path
  * @param urlEncode        flag whether the URI needs to be encoded
  */
case class UriMappingConfig(removePrefix: String, removeComponents: Int, uriTemplate: String,
                            pathSeparator: String, urlEncode: Boolean) extends UriMappingSpec {
  override val prefixToRemove: String = removePrefix

  override val pathComponentsToRemove: Int = removeComponents

  override val urlEncoding: Boolean = urlEncode

  override val uriPathSeparator: String = pathSeparator
}

/**
  * A class defining the configuration settings to be applied for an HTTP
  * archive.
  *
  * @param archiveURI            the URI of the HTTP media archive
  * @param archiveName           a name for the HTTP media archive
  * @param processorCount        the number of parallel processor actors to be used
  *                              when downloading meta data from the archive
  * @param processorTimeout      the timeout for calls to processor actors
  * @param propagationBufSize    number of processed media whose content can be
  *                              propagated to the union archive in parallel
  * @param maxContentSize        the maximum size of a content file (either a
  *                              settings or a meta data file) in kilobytes; if a
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
  * @param metaMappingConfig     configuration for URI mapping for meta data
  * @param contentMappingConfig  configuration for URI mapping for the archive
  *                              content file
  * @param downloader            the object to download media files
  */
case class HttpArchiveConfig(archiveURI: Uri,
                             archiveName: String,
                             processorCount: Int,
                             processorTimeout: Timeout,
                             propagationBufSize: Int,
                             maxContentSize: Int,
                             downloadBufferSize: Int,
                             downloadMaxInactivity: FiniteDuration,
                             downloadReadChunkSize: Int,
                             timeoutReadSize: Int,
                             downloadConfig: DownloadConfig,
                             metaMappingConfig: UriMappingConfig,
                             contentMappingConfig: UriMappingConfig,
                             downloader: MediaDownloader)
