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

import com.github.cloudfiles.core.http.HttpRequestSender
import de.oliver_heger.linedj.archive.cloud.{ArchiveCryptConfig, CloudArchiveConfig}
import de.oliver_heger.linedj.archive.cloud.auth.{AuthMethod, BasicAuthMethod, OAuthMethod}
import de.oliver_heger.linedj.archive.cloud.spi.CloudArchiveFileSystemFactory
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.Configuration
import org.apache.commons.configuration2.ex.ConfigurationException
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.Timeout

import java.nio.file.{Path, Paths}
import java.util.Locale
import scala.util.Try

object ArchiveConfig:
  /**
    * An implicit conversion that creates a [[CloudArchiveConfig]] from an
    * [[ArchiveConfig]] mapping the properties as appropriate. Note that this
    * function assumes that the properties in the source configuration are
    * valid; so no specific error handling is done.
    */
  given Conversion[ArchiveConfig, CloudArchiveConfig] with
    override def apply(c: ArchiveConfig): CloudArchiveConfig =
      CloudArchiveConfig(
        archiveBaseUri = Uri(c.archiveBaseUri),
        archiveName = c.archiveName,
        authMethod = c.authMethod,
        contentPath = c.optContentPath.map(p => Uri.Path(p)).getOrElse(CloudArchiveConfig.DefaultContentPath),
        mediaPath = c.optMediaPath.map(Uri.Path(_)).getOrElse(CloudArchiveConfig.DefaultMediaPath),
        metadataPath = c.optMetadataPath.map(Uri.Path(_)).getOrElse(CloudArchiveConfig.DefaultMetadataPath),
        parallelism = c.optParallelism.getOrElse(CloudArchiveConfig.DefaultParallelism),
        maxContentSize = c.optMaxContentSize.getOrElse(CloudArchiveConfig.DefaultMaxContentSize),
        requestQueueSize = c.optRequestQueueSize.getOrElse(HttpRequestSender.DefaultQueueSize),
        timeout = c.optTimeout.getOrElse(CloudArchiveConfig.DefaultArchiveTimeout),
        optCryptConfig = c.optCryptConfig,
        fileSystemFactory = CloudArchiveFileSystemFactory.getFactory(c.fileSystem)
      )
end ArchiveConfig

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
  * @param fileSystem          the type of the file system to access this
  *                            archive
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
                         fileSystem: String,
                         optContentPath: Option[String],
                         optMediaPath: Option[String],
                         optMetadataPath: Option[String],
                         optParallelism: Option[Int],
                         optMaxContentSize: Option[Int],
                         optRequestQueueSize: Option[Int],
                         optTimeout: Option[Timeout],
                         optCryptConfig: Option[ArchiveCryptConfig])

object CloudArchiveServerConfig:
  /** The name of the section storing the relevant configuration properties. */
  private val ConfigSection = "media.cloudArchives."

  /**
    * The name of the configuration property for the path of the directory in
    * which credential files and OAuth configurations are expected.
    */
  private val PropCredDirectory = ConfigSection + "credentialsDirectory"

  /**
    * The name of the configuration property defining the root path of the
    * local cache for archive metadata.
    */
  private val PropCacheDirectory = ConfigSection + "cacheDirectory"

  /**
    * The name of the configuration property for the list of cloud archives to
    * be managed by this server application.
    */
  private val PropCloudArchives = ConfigSection + "cloudArchive"

  /**
    * The name of the configuration property defining the name of a cloud
    * archive.
    */
  private val PropArchiveName = "name"

  /**
    * The name of the configuration property defining the base URI of a cloud
    * archive.
    */
  private val PropArchiveUri = "baseUri"

  /**
    * The name of the configuration property to specify the type of the
    * authentication method required to access this archive. This must be a
    * supported type like "BASIC" or "OAUTH", ignoring case.
    */
  private val PropAuthMethod = "authMethod"

  /**
    * The name of the configuration property for the authentication realm.
    * This property, together with ''PropAuthMethod'' is used to construct an
    * [[AuthMethod]] object.
    */
  private val PropAuthRealm = "authRealm"

  /**
    * The name of the configuration property defining the type of the
    * ''CloudFiles'' file system to access the cloud archive. The value must be
    * the name of a factory that is available on the classpath (ignoring case).
    */
  private val PropFileSystem = "fileSystem"

  /**
    * The name of the optional configuration property to set the path to the
    * "table of contents" document for this archive.
    */
  private val PropContentPath = "contentPath"

  /**
    * The name of the optional configuration property to set the root path for
    * the media data contained in this archive.
    */
  private val PropMediaPath = "mediaPath"

  /**
    * The name of the optional configuration property to the set path under
    * which metadata files are located in this archive.
    */
  private val PropMetadataPath = "metadataPath"

  /**
    * The name of the optional configuration property defining the parallelism
    * to use when downloading metadata from this archive. This roughly
    * corresponds to the number of media for which metadata is downloaded
    * concurrently.
    */
  private val PropParallelism = "parallelism"

  /**
    * The name of the optional configuration property defining a maximum size
    * for metadata documents. If a document has a larger size, its download is
    * aborted, and the medium is ignored.
    */
  private val PropMaxSize = "maxContentSize"

  /**
    * The name of the optional configuration property defining the size of the
    * queue for HTTP requests sent to this archive.
    */
  private val PropQueueSize = "requestQueueSize"

  /**
    * The name of the optional configuration property defining a timeout when
    * accessing this archive. The value can be a number with an optional unit.
    */
  private val PropTimeout = "timeout"

  /**
    * The name of the optional configuration property defining the size of the
    * cache for encrypted file names. If this property is present, the archive
    * is considered to be encrypted.
    */
  private val PropCryptCacheSize = "cryptCacheSize"

  /**
    * The name of the optional configuration property defining the size of
    * chunks in which file names are decrypted. If this property is present,
    * the archive is considered to be encrypted.
    */
  private val PropCryptChunkSize = "cryptChunkSize"

  /**
    * The name of the optional configuration property to indicate that this
    * archive is encrypted. If it is set to '''true''', all other properties
    * related to encryption are set to their default values unless they are
    * defined explicitly. If it is set to '''false''', encryption-related
    * properties are ignored.
    */
  private val PropEncrypted = "[@encrypted]"

  /**
    * A set of properties that are related to encryption. If one of these
    * properties is present, the archive is considered to be encrypted.
    */
  private val EncryptionProperties = Set(PropCryptCacheSize, PropCryptChunkSize)

  /**
    * Parses a configuration for this server application and returns a
    * corresponding [[CloudArchiveServerConfig]]. The operation fails if the
    * configuration is invalid.
    *
    * @param config the configuration to parse
    * @return a [[Try]] with the resulting server configuration
    */
  def parseConfig(config: Configuration): Try[CloudArchiveServerConfig] = Try:
    CloudArchiveServerConfig(
      credentialsDirectory = Paths.get(mandatoryProperty(config, PropCredDirectory)),
      cacheDirectory = Paths.get(mandatoryProperty(config, PropCacheDirectory)),
      archives = parseArchives(config)
    )

  /**
    * Parses the definitions of the cloud archives to be managed from the given
    * configuration. Throws an exception if invalid configuration options are
    * detected.
    *
    * @param config the configuration to parse
    * @return a [[List]] with the extracted archive configurations
    */
  private def parseArchives(config: Configuration): List[ArchiveConfig] =
    val archives = config.getList(PropCloudArchives + "." + PropArchiveName)
    (0 until archives.size()).map: index =>
      parseArchive(config, s"$PropCloudArchives($index)")
    .toList

  /**
    * Parses the properties of a specific cloud archive from the server 
    * configuration.
    *
    * @param config the configuration
    * @param key    the key prefix for the archive in question
    * @return the extracted archive configuration
    */
  private def parseArchive(config: Configuration, key: String): ArchiveConfig =
    val archiveConfig = config.subset(key)
    ArchiveConfig(
      archiveName = mandatoryProperty(archiveConfig, PropArchiveName),
      archiveBaseUri = mandatoryProperty(archiveConfig, PropArchiveUri),
      authMethod = parseAuthMethod(archiveConfig),
      fileSystem = parseFileSystem(archiveConfig),
      optContentPath = optionalStringProperty(archiveConfig, PropContentPath),
      optMediaPath = optionalStringProperty(archiveConfig, PropMediaPath),
      optMetadataPath = optionalStringProperty(archiveConfig, PropMetadataPath),
      optParallelism = optionalIntProperty(archiveConfig, PropParallelism),
      optMaxContentSize = optionalIntProperty(archiveConfig, PropMaxSize),
      optRequestQueueSize = optionalIntProperty(archiveConfig, PropQueueSize),
      optTimeout = optionalTimeoutProperty(archiveConfig, PropTimeout),
      optCryptConfig = parseCryptConfig(archiveConfig)
    )

  /**
    * Returns the configuration property with the given key or throws a
    * meaningful exception if it is undefined.
    *
    * @param config the configuration
    * @param key    the desired key
    * @return the string value of this property
    */
  private def mandatoryProperty(config: Configuration, key: String): String =
    optionalStringProperty(config, key)
      .getOrElse(throw new ConfigurationException(s"Missing mandatory property '$key'."))

  /**
    * Returns an [[Option]] for the value of an optional configuration
    * property.
    *
    * @param config the configuration
    * @param key    the desired key
    * @return an [[Option]] for the value of this property
    */
  private def optionalStringProperty(config: Configuration, key: String): Option[String] =
    Option(config.getString(key))

  /**
    * Returns an [[Option]] for the value of an optional '''Int'''
    * configuration property.
    *
    * @param config the configuration
    * @param key    the desired key
    * @return an [[Option]] for the '''Int''' value of this property
    */
  private def optionalIntProperty(config: Configuration, key: String): Option[Int] =
    if config.containsKey(key) then
      Some(config.getInt(key))
    else
      None

  /**
    * Returns an [[Option]] for the value of an optional configuration property
    * of type [[Timeout]].
    *
    * @param config the configuration
    * @param key    the desired key
    * @return an [[Option]] for the [[Timeout]] value of this property
    */
  private def optionalTimeoutProperty(config: Configuration, key: String): Option[Timeout] =
    optionalStringProperty(config, key) map : strValue =>
      Timeout(strValue.toDuration.get)

  /**
    * Extracts the [[AuthMethod]] of an archive from the configuration. Throws
    * an exception if the method is invalid.
    *
    * @param config the configuration for the current archive
    * @return the [[AuthMethod]] for this archive
    */
  private def parseAuthMethod(config: Configuration): AuthMethod =
    val methodType = mandatoryProperty(config, PropAuthMethod)
    val realm = mandatoryProperty(config, PropAuthRealm)
    methodType.toLowerCase(Locale.ROOT) match
      case "basic" => BasicAuthMethod(realm)
      case "oauth" => OAuthMethod(realm)
      case _ => throw new ConfigurationException(s"Invalid value for '$PropAuthMethod': '$methodType'.")

  /**
    * Extracts the type of the file system to be used for this archive from the
    * configuration and checks whether the specified type is valid. Throws an
    * exception if this declaration is invalid.
    *
    * @param config the configuration
    * @return the type of the file system for this archive
    */
  private def parseFileSystem(config: Configuration): String =
    val fileSystem = mandatoryProperty(config, PropFileSystem)
    if !CloudArchiveFileSystemFactory.existsFactory(fileSystem) then
      throw new ConfigurationException(s"Invalid file system type: '$fileSystem'.")
    fileSystem

  /**
    * Parses the configuration related to encryption for a specific cloud
    * archive. Whether the archive is encrypted is determined by the
    * ''encrypted'' flag. If this is missing, the archive is treated as
    * encrypted if there is at least one encryption-related property.
    *
    * @param config the configuration for the current archive
    * @return the optional [[ArchiveCryptConfig]] for this archive
    */
  private def parseCryptConfig(config: Configuration): Option[ArchiveCryptConfig] =
    val encrypted = if config.containsKey(PropEncrypted) then
      config.getBoolean(PropEncrypted)
    else
      EncryptionProperties.exists(config.containsKey)

    if encrypted then
      val cryptConfig = ArchiveCryptConfig(
        cryptCacheSize = config.getInt(PropCryptCacheSize, CloudArchiveConfig.DefaultCryptConfig.cryptCacheSize),
        cryptChunkSize = config.getInt(PropCryptChunkSize, CloudArchiveConfig.DefaultCryptConfig.cryptChunkSize)
      )
      Some(cryptConfig)
    else
      None
end CloudArchiveServerConfig

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
