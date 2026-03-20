/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.cloud.ArchiveCryptConfig
import de.oliver_heger.linedj.archive.cloud.auth.AuthMethod
import org.apache.pekko.util.Timeout

import java.nio.file.Path

/**
  * A data class storing the configuration properties of a single cloud archive
  * to be managed by the cloud archive server. Based on an instance, a
  * configuration to access the corresponding archive is constructed;
  * therefore, this class defines similar properties. Instances are created
  * while parsing the configuration file of the cloud archive server.
  *
  * @param archiveBaseUri      the base URI for this archive
  * @param archiveName         a human-readable name for this archive
  * @param authMethod          the method to authenticate against this archive
  * @param optContentPath      the optional path to the archive's content
  *                            document if it differs from the default
  * @param optMediaPath        the optional root path for media files if it
  *                            differs from the default
  * @param optMetadataPath     the optional path to metadata files if it
  *                            differs from the default
  * @param optParallelism      the optional number of parallel requests that
  *                            can be sent to the archive when downloading
  *                            metadata files if it differs from the default
  * @param optMaxContentSize   the optional maximum size of content documents
  *                            that are downloaded if it differs from the
  *                            default
  * @param optRequestQueueSize the optional size of the HTTP request queue if
  *                            it differs from the default
  * @param optTimeout          the optional timeout when accessing the
  *                            archive if it differs from the default
  * @param optCryptConfig      an optional configuration to decrypt the
  *                            archive content if the archive is encrypted
  */
case class ArchiveConfig(archiveBaseUri: String,
                         archiveName: String,
                         authMethod: AuthMethod,
                         optContentPath: Option[String],
                         optMediaPath: Option[String],
                         optMetadataPath: Option[String],
                         optParallelism: Option[Int],
                         optMaxContentSize: Option[Int],
                         optRequestQueueSize: Option[Int],
                         optTimeout: Option[Timeout],
                         optCryptConfig: Option[ArchiveCryptConfig])

/**
  * A data class holding the specific configuration for the cloud archive
  * server. This includes the configuration of the cloud archives to be
  * managed and some general properties required to access these archives.
  *
  * @param archives             the list of cloud archives with their properties
  * @param credentialsDirectory the local path which stores files with
  *                             credentials
  * @param cacheDirectory       the root path to be used by the local cache
  *                             for archive content documents
  */
case class CloudArchiveServerConfig(archives: List[ArchiveConfig],
                                    credentialsDirectory: Path,
                                    cacheDirectory: Path)
