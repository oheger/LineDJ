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

package de.oliver_heger.linedj.archive.config

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig.MediaArchiveConfigLoader
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.shared.config.ConfigExtensions
import org.apache.commons.configuration.Configuration
import org.apache.logging.log4j.LogManager

import java.nio.file.{Path, Paths}
import java.util.Locale
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
  * An object providing a [[MediaArchiveConfigLoader]] implementation for the
  * Apache Commons Configuration 1.x library.
  */
object MediaArchiveConfigLoaderCC1:
  given loader: MediaArchiveConfigLoader[Configuration] = new MediaArchiveConfigLoaderCC1
end MediaArchiveConfigLoaderCC1

private class MediaArchiveConfigLoaderCC1 extends MediaArchiveConfigLoader[Configuration]:

  import MediaArchiveConfig.*

  private val Log = LogManager.getLogger(classOf[MediaArchiveConfigLoaderCC1])

  override def loadMediaArchiveConfigs(config: Configuration, nameResolver: => String): Seq[MediaArchiveConfig] =
    val defDownloadConfig = parseDownloadConfig(config.subset(ArchivesSection), DownloadConfig.DefaultDownloadConfig)
    val archives = config.getList(ArchivesKey + "." + PropRootPath)
    (0 until archives.size()) map : idx =>
      val key = s"$ArchivesKey($idx)"
      extractMediaArchiveConfig(config, defDownloadConfig, key, nameResolver)

  /**
    * Extracts the configuration for a single local archive from the given key
    * and the configuration passed in. The properties below this key are read,
    * default values are obtained from the section about local archives.
    *
    * @param config            the ''Configuration'' to be processed
    * @param defDownloadConfig the download config with default settings
    * @param keyPrefix         the prefix of the keys to be processed
    * @param nameResolver      a function for resolving the archive names
    * @return the ''MediaArchiveConfig'' that has been constructed
    */
  private def extractMediaArchiveConfig(config: Configuration,
                                        defDownloadConfig: DownloadConfig,
                                        keyPrefix: String,
                                        nameResolver: => String): MediaArchiveConfig =
    val subConfig = config.subset(keyPrefix)
    new MediaArchiveConfig(
      metadataReadChunkSize = intProperty(config, subConfig, PropMetaDataReadChunkSize),
      infoSizeLimit = intProperty(config, subConfig, PropInfoSizeLimit),
      tagSizeLimit = intProperty(config, subConfig, PropTagSizeLimit),
      processingTimeout = durationProperty(config, subConfig, PropProcessingTimeout),
      metadataMediaBufferSize = intProperty(config, subConfig, PropMetaDataBufferSize,
        Some(DefaultMetaDataMediaBufferSize)),
      metadataPersistencePath = Paths.get(stringProperty(config, subConfig, PropMetaDataPersistencePath)),
      metadataPersistenceChunkSize = intProperty(config, subConfig, PropMetaDataPersistenceChunkSize),
      metadataPersistenceParallelCount = intProperty(config, subConfig, PropMetaDataPersistenceParallelCount),
      metadataPersistenceWriteBlockSize = intProperty(config, subConfig, PropMetaDataPersistenceWriteBlockSize),
      excludedFileExtensions = obtainExcludedExtensions(config, subConfig),
      includedFileExtensions = obtainIncludedExtensions(config, subConfig),
      rootPath = Paths get stringProperty(config, subConfig, PropRootPath),
      processorCount = intProperty(config, subConfig, PropProcessorCount, Some(DefaultProcessorCount)),
      contentFile = extractTocPath(config, subConfig),
      downloadConfig = parseDownloadConfig(subConfig, defDownloadConfig),
      archiveName = resolveArchiveName(subConfig, nameResolver),
      infoParserTimeout = durationProperty(config, subConfig, PropScanParseInfoTimeout,
        Some(DefaultInfoParserTimeout.duration)),
      scanMediaBufferSize = intProperty(config, subConfig, PropScanMediaBufferSize, Some(DefaultScanMediaBufferSize)),
      blockingDispatcherName = subConfig.getString(PropBlockingDispatcherName, DefaultBlockingDispatcherName)
    )

  /**
    * Resolves the archive name using the resolver function. Also handles
    * exceptions; in this case, the name pattern is returned as is.
    *
    * @param config       the application configuration
    * @param nameResolver the resolver function
    * @return the archive name
    */
  private def resolveArchiveName(config: Configuration, nameResolver: => String): String =
    val pattern = config.getString(PropArchiveName, DefaultNamePattern)
    if pattern contains PlaceholderHost then
      Try(pattern.replace(PlaceholderHost, nameResolver)) match
        case Success(name) => name
        case Failure(ex) =>
          Log.error("Could not resolve media archive name!", ex)
          pattern
    else pattern

  /**
    * Determines the value of a string property using the value from the
    * defaults section if necessary.
    *
    * @param c         the global configuration
    * @param subConfig the sub config transformed for the current key
    * @param key       the key of the property
    * @return the value of this string property
    */
  private def stringProperty(c: Configuration, subConfig: Configuration, key: String): String =
    subConfig.getString(key, c.getString(ArchivesSection + key))

  /**
    * Determines the value of an int property using the value from the
    * defaults section if necessary or the optional default value.
    *
    * @param c          the global configuration
    * @param subConfig  the sub config transformed for the current key
    * @param key        the key of the property
    * @param optDefault the optional default value
    * @return the value of this int property
    */
  private def intProperty(c: Configuration, subConfig: Configuration, key: String, optDefault: Option[Int] = None):
  Int = if subConfig.containsKey(key) then subConfig getInt key
  else optDefault.fold(c.getInt(ArchivesSection + key)): defValue =>
    c.getInt(ArchivesSection + key, defValue)

  /**
    * Reads a property from the given configuration object and converts it to a
    * duration, taking the section with default values into account if
    * necessary. The configuration property can have an optional unit for the
    * duration.
    *
    * @param c          the global configuration
    * @param subConfig  the sub config transformed for the current key
    * @param key        the key
    * @param optDefault the optional default value
    * @return the resulting duration
    */
  private def durationProperty(c: Configuration,
                               subConfig: Configuration,
                               key: String,
                               optDefault: Option[FiniteDuration] = None): FiniteDuration =
    def getDuration(config: Configuration, key: String): Try[FiniteDuration] =
      ConfigExtensions.toDurationFromTypes(config.getInt(key), config.getString(key))

    val triedDuration = if subConfig.containsKey(key) then
      getDuration(subConfig, key)
    else
      optDefault.fold(getDuration(c, ArchivesSection + key)): defValue =>
        if c.containsKey(ArchivesSection + key) then getDuration(c, ArchivesSection + key)
        else Success(defValue)
    triedDuration.get

  /**
    * Determines the set with file extensions to be excluded from the given
    * Commons Configuration object.
    *
    * @param c         the global configuration
    * @param subConfig the sub configuration transformed for the current key
    * @return the set with excluded file extensions
    */
  private def obtainExcludedExtensions(c: Configuration, subConfig: Configuration): Set[String] =
    obtainExtensionSet(c, subConfig, PropExcludedExtensions)

  /**
    * Determines the set with file extensions to be included from the given
    * Commons configuration object.
    *
    * @param c         the global configuration
    * @param subConfig the sub configuration transformed for the current key
    * @return the set with included file extensions
    */
  private def obtainIncludedExtensions(c: Configuration, subConfig: Configuration): Set[String] =
    obtainExtensionSet(c, subConfig, PropIncludedExtensions)

  /**
    * Generates a set with file extensions from the configuration.
    *
    * @param c         the global configuration
    * @param subConfig the sub configuration transformed for the current key
    * @param key       the key to be evaluated
    * @return the set with exclusions
    */
  private def obtainExtensionSet(c: Configuration, subConfig: Configuration, key: String): Set[String] =
    import scala.jdk.CollectionConverters.*
    val extensions = subConfig.getList(key, c.getList(ArchivesSection + key))
    extensions.asScala.map(_.toString.toUpperCase(Locale.ROOT)).toSet

  /**
    * Extracts the path for the file where to store the ToC (if any) from the
    * given configuration.
    *
    * @param c         the configuration
    * @param subConfig the sub configuration transformed for the current key
    * @return an ''Option'' for the path extracted
    */
  private def extractTocPath(c: Configuration, subConfig: Configuration): Option[Path] =
    Option(stringProperty(c, subConfig, PropTocFile)).map(Paths.get(_))

  /**
    * Extracts the data of a [[DownloadConfig]] from the given configuration
    * object.
    *
    * @param config    the configuration
    * @param defConfig a default download configuration to use for undefined
    *                  properties in the configuration to parse
    * @return the extracted [[DownloadConfig]]
    */
  private def parseDownloadConfig(config: Configuration, defConfig: DownloadConfig): DownloadConfig =
    new DownloadConfig(
      downloadTimeout = durationProperty(
        config,
        config,
        DownloadConfig.PropDownloadActorTimeout,
        Some(defConfig.downloadTimeout)
      ),
      downloadCheckInterval = durationProperty(
        config,
        config,
        DownloadConfig.PropDownloadCheckInterval,
        Some(defConfig.downloadCheckInterval)
      ),
      downloadChunkSize = config.getInt(DownloadConfig.PropDownloadChunkSize, defConfig.downloadChunkSize)
    )
