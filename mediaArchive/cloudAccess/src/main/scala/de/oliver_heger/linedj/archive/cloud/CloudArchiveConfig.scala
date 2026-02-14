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

package de.oliver_heger.linedj.archive.cloud

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.archive.cloud.auth.AuthMethod
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.DurationInt

/**
  * A data class storing configuration options for an encrypted archive.
  *
  * @param cryptCacheSize the size of the cache for decrypted paths
  * @param cryptChunkSize the chunk size for decrypt operations
  */
case class ArchiveCryptConfig(cryptCacheSize: Int,
                              cryptChunkSize: Int)

object CloudArchiveConfig:
  /**
    * The default value of the parallelism property for cloud archives.
    */
  final val DefaultParallelism = 4

  /**
    * The default maximum size for content files loaded from a cloud archive
    * (in kilobytes). Responses with a larger size will be canceled.
    */
  final val DefaultMaxContentSize = 64

  /** A default timeout value for cloud archives. */
  final val DefaultArchiveTimeout = Timeout(30.seconds)

  /** The default content path for cloud archives. */
  final val DefaultContentPath = Uri.Path("content.json")

  /**
    * The default path under which media files are located in cloud archives
    * conforming to the expected standard layout.
    */
  final val DefaultMediaPath = Uri.Path("media")

  /**
    * The default path under which metadata files are located in cloud archives
    * conforming to the expected standard layout.
    */
  final val DefaultMetadataPath = Uri.Path("mdt")

  /**
    * A configuration for encrypted archives setting default values for the
    * single configuration options.
    */
  final val DefaultCryptConfig = ArchiveCryptConfig(
    cryptChunkSize = 32,
    cryptCacheSize = 1024
  )
end CloudArchiveConfig

/**
  * A data class collecting configuration options for a media archive stored in
  * the cloud.
  *
  * The information provided by an instance is sufficient to set up a
  * connection to the archive and download files from it. The archive can be
  * encrypted (both the file names and the content of files); in this case, an
  * [[ArchiveCryptConfig]] must be provided.
  *
  * Note that this class does not define any credentials to access the archive.
  * The authentication is handled by other services which obtain credentials
  * dynamically based on the archive's name.
  *
  * @param archiveBaseUri    the base URI of the cloud archive; all
  *                          paths are resolved against this URI
  * @param archiveName       a name for this cloud archive
  * @param fileSystemFactory the factory for creating the file system for this
  *                          archive
  * @param authMethod        the authentication method for this archive
  * @param contentPath       the (relative) path to the content file
  * @param mediaPath         the (relative) base path for all media files
  * @param metadataPath      the (relative) path under which all metadata
  *                          files are located
  * @param parallelism       the number of parallel requests that can be sent
  *                          when downloading metadata from the archive
  * @param maxContentSize    the maximum size of a content file (either a
  *                          settings or a metadata file) in kilobytes; if a
  *                          file is larger, it's download is canceled
  * @param requestQueueSize  the size of the request queue of the request
  *                          actor
  * @param timeout           a general timeout for operations against this
  *                          archive
  * @param optCryptConfig    an optional [[ArchiveCryptConfig]]; if present,
  *                          the archive is treated as encrypted based on the
  *                          contained parameters
  * @param optActorBaseName  an optional base name for actors that are created
  *                          for this cloud archive; if not specified, the name
  *                          is derived from the archive name
  */
case class CloudArchiveConfig(archiveBaseUri: Uri,
                              archiveName: String,
                              fileSystemFactory: CloudArchiveFileSystemFactory,
                              authMethod: AuthMethod,
                              contentPath: Uri.Path = CloudArchiveConfig.DefaultContentPath,
                              mediaPath: Uri.Path = CloudArchiveConfig.DefaultMediaPath,
                              metadataPath: Uri.Path = CloudArchiveConfig.DefaultMetadataPath,
                              parallelism: Int = CloudArchiveConfig.DefaultParallelism,
                              maxContentSize: Int = CloudArchiveConfig.DefaultMaxContentSize,
                              requestQueueSize: Int = HttpRequestSender.DefaultQueueSize,
                              timeout: Timeout = CloudArchiveConfig.DefaultArchiveTimeout,
                              optCryptConfig: Option[ArchiveCryptConfig] = None,
                              optActorBaseName: Option[String] = None)
