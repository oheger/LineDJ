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

import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import org.apache.commons.configuration.Configuration
import org.apache.logging.log4j.LogManager
import org.apache.pekko.util.Timeout

import java.nio.file.{Path, Paths}
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Seq
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object MediaArchiveConfig:
  /**
    * Constant for the section with configuration settings about local
    * archives.
    */
  val ArchivesSection = "media.localArchives."

  /** Constant for the key prefix under which all options are located. */
  val ArchivesKey: String = ArchivesSection + "localArchive"

  /** The configuration property for the info size restriction. */
  val PropInfoSizeLimit: String = "infoSizeLimit"

  /** The configuration property for the excluded file extensions. */
  val PropExcludedExtensions: String = "excludedExtensions"

  /**
    * The configuration property for file extensions to be included when
    * scanning for media files. If this property is defined, only files with an
    * extension defined here are taken into account. Note that this property
    * takes precedence over excluded file extensions.
    */
  val PropIncludedExtensions: String = "includedExtensions"

  /** Constant for the prefix for the metadata extraction configuration. */
  val MetaExtractionPrefix: String = "metaDataExtraction."

  /** The configuration property for metadata extraction chunk size. */
  val PropMetaDataReadChunkSize: String = MetaExtractionPrefix + "readChunkSize"

  /** The configuration property for the size restriction for ID3 tags. */
  val PropTagSizeLimit: String = MetaExtractionPrefix + "tagSizeLimit"

  /** The configuration property for the processing timeout. */
  val PropProcessingTimeout: String = MetaExtractionPrefix + "processingTimeout"

  /**
    * The configuration property defining a maximum buffer size for the
    * processing of media during metadata extraction. When the content of a
    * medium has been determined during a media scan operation it is sent to
    * the metadata manager actor which then triggers the processing of this
    * medium. As this may take a while, more media may be sent before their
    * processing is complete. This property defines the maximum size of a
    * buffer for such media. As long as the current number of media in this
    * buffer is below this threshold, the metadata manager sends ACK
    * responses; so the scan operation can continue. When the limit is reached,
    * however, no ACK is sent - pausing the scan operation - until media have
    * been processed completely. This property has a direct impact on the
    * memory consumption during metadata extraction.
    */
  val PropMetaDataBufferSize: String = MetaExtractionPrefix + "metaDataMediaBufferSize"

  /** Constant for the prefix for the metadata persistence configuration. */
  val MetaPersistencePrefix: String = "metaDataPersistence."

  /** The configuration property for the metadata persistence path. */
  val PropMetaDataPersistencePath: String = MetaPersistencePrefix + "path"

  /** The configuration property for the metadata persistence chunk size. */
  val PropMetaDataPersistenceChunkSize: String = MetaPersistencePrefix + "chunkSize"

  /** The configuration property for the metadata persistence parallel count. */
  val PropMetaDataPersistenceParallelCount: String = MetaPersistencePrefix + "parallelCount"

  /** The configuration property for the metadata persistence write block size. */
  val PropMetaDataPersistenceWriteBlockSize: String = MetaPersistencePrefix + "writeBlockSize"

  /**
    * Constant for the property defining the root directory of the media
    * archive. The directory structure below this path is scanned for media
    * files.
    */
  val PropRootPath: String = "rootPath"

  /**
    * The configuration property defining the number of processors to be used
    * when extracting metadata. If the hard disc is powerful, it can make
    * sense to set a higher number to speed up metadata extraction. The
    * default value is 1.
    */
  val PropProcessorCount: String = "processorCount"

  /**
    * The configuration property to define the archive's name. This can be a
    * string that may contain the placeholder ''${host}''. This placeholder is
    * replaced by the locale network name of the current machine. If it is
    * missing, a static name can be specified for the archive. The property is
    * optional; if it is undefined, its value is assumed to be the
    * ''${host}-media-archive''.
    */
  val PropArchiveName: String = "archiveName"

  /** The configuration property for the file where to store the archive ToC. */
  val PropTocFile: String = MetaPersistencePrefix + "tocFile"

  /** Prefix for properties related to media archive scan operations. */
  val ScanPrefix: String = "scan."

  /**
    * Configuration property for the timeout for parsing a medium description
    * file (in seconds). Medium description files (with the extension
    * ''.settings'') are parsed in parallel while the directory structure of a
    * media archive is scanned. If the parsing result does not arrive within
    * this time span, an error is assumed.
    */
  val PropScanParseInfoTimeout: String = ScanPrefix + "parseInfoTimeout"

  /**
    * Configuration property determining the size of the buffer for media
    * during a scan operation. During such an operation files are assigned to
    * the media they belong to, and completed media are passed to the media
    * manager actor. If processing of completed media takes longer, the stream
    * for scanning the directory structure has to be slowed down. With this
    * property a buffer for completed media is defined. The given number of
    * media is buffered; only if there are more completed media, the stream has
    * to wait until the media manager has processed results.
    */
  val PropScanMediaBufferSize: String = ScanPrefix + "mediaBufferSize"

  /**
    * Placeholder for the host name which can occur in the configured archive
    * name.
    */
  val PlaceholderHost = "${host}"

  /**
    * Suffix for generating a default archive name. Per default, this suffix is
    * appended to the local host name to generate the archive name.
    */
  val DefaultNameSuffix = "-media-archive"

  /**
    * The default name pattern for generating the archive name. If the property
    * for the archive name is not defined, this value is used.
    */
  val DefaultNamePattern: String = PlaceholderHost + DefaultNameSuffix

  /** The default timeout for parsing a medium description file. */
  val DefaultInfoParserTimeout: Timeout = Timeout(1.minute)

  /** The default value for the ''PropMetaDataBufferSize'' property. */
  val DefaultMetaDataMediaBufferSize = 2

  /** The default value for the ''PropScanMediaBufferSize'' property. */
  val DefaultScanMediaBufferSize = 8

  /** The default number of processors for metadata extraction. */
  val DefaultProcessorCount = 1

  /**
    * The default number of path components to be removed from URIs of medium
    * description files when writing the archive's ToC.
    */
  val DefaultTocDescRemovePathComponents = 0

  /** The logger. */
  private val Log = LogManager.getLogger(classOf[MediaArchiveConfig])

  /**
    * Processes the passed in configuration and extracts [[MediaArchiveConfig]]
    * objects for all archives that are listed in the configuration.
    *
    * @param config       the ''Configuration'' to be processed
    * @param nameResolver a function for resolving the archive names
    * @return a sequence with the archive configurations extracted
    */
  def apply(config: Configuration,
            nameResolver: => String = LocalNameResolver.localHostName): Seq[MediaArchiveConfig] =
    val defDownloadConfig = DownloadConfig(config.subset(ArchivesSection))
    val archives = config.getList(ArchivesKey + "." + PropRootPath)
    (0 until archives.size()) map { idx =>
      val key = s"$ArchivesKey($idx)"
      extractMediaArchiveConfig(config, defDownloadConfig, key, nameResolver)
    }

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
  private def extractMediaArchiveConfig(config: Configuration, defDownloadConfig: DownloadConfig,
                                        keyPrefix: String, nameResolver: => String): MediaArchiveConfig =
    val subConfig = config.subset(keyPrefix)
    new MediaArchiveConfig(metadataReadChunkSize = intProperty(config, subConfig, PropMetaDataReadChunkSize),
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
      downloadConfig = DownloadConfig(subConfig, defDownloadConfig),
      archiveName = resolveArchiveName(subConfig, nameResolver),
      infoParserTimeout = durationProperty(config, subConfig, PropScanParseInfoTimeout,
        Some(DefaultInfoParserTimeout.duration)),
      scanMediaBufferSize = intProperty(config, subConfig, PropScanMediaBufferSize, Some(DefaultScanMediaBufferSize)))

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
  else optDefault.fold(c.getInt(ArchivesSection + key)) { defValue =>
    c.getInt(ArchivesSection + key, defValue)
  }

  /**
    * Reads a property from the given configuration object and converts it to a
    * duration, taking the section with default values into account if
    * necessary.
    *
    * @param c          the global configuration
    * @param subConfig  the sub config transformed for the current key
    * @param key        the key
    * @param optDefault the optional default value
    * @return the resulting duration
    */
  private def durationProperty(c: Configuration, subConfig: Configuration, key: String,
                               optDefault: Option[FiniteDuration] = None): FiniteDuration =
    def toDuration(secs: Long): FiniteDuration = FiniteDuration(secs, TimeUnit.SECONDS)

    if subConfig.containsKey(key) then toDuration(subConfig getLong key)
    else optDefault.fold(toDuration(c.getLong(ArchivesSection + key))) { defValue =>
      if c.containsKey(ArchivesSection + key) then toDuration(c.getLong(ArchivesSection + key))
      else defValue
    }

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
    import scala.jdk.CollectionConverters._
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
    * A data class storing information about a media root. A media root is a
    * directory structure that contains media files. In addition to the actual
    * root path, some metadata is provided which is needed while processing
    * this structure.
    *
    * @param rootPath          the root path to the structure
    * @param processorCount    the number of parallel processors during metadata
    *                          extraction
    * @param accessRestriction an optional restriction for parallel reads; for
    *                          instance, a CD-ROM drive should not be read
    *                          simultaneously by multiple threads
    */
  case class MediaRootData(rootPath: Path, processorCount: Int, accessRestriction: Option[Int])


/**
  * A class for managing configuration data for the server-side actor system.
  *
  * This class is a thin wrapper around the default configuration of an actor
  * system. It offers methods for accessing specific configuration properties.
  * Instances are created using a static factory method.
  *
  * @param downloadConfig                    the configuration for download operations
  * @param metadataReadChunkSize             the read chunk size when extracting metadata
  * @param infoSizeLimit                     the maximum size of a medium description file
  * @param tagSizeLimit                      the size limit for ID3 tags
  * @param processingTimeout                 a timeout for the processing of a single media
  *                                          file
  * @param metadataMediaBufferSize           the buffer for metadata extraction
  * @param metadataPersistencePath           the path used by metadata persistence; here
  *                                          extracted metadata is stored in files
  * @param metadataPersistenceChunkSize      the chunk size to be used when reading
  *                                          or writing files with persistent
  *                                          metadata
  * @param metadataPersistenceParallelCount  the number of parallel reader or
  *                                          writer actors to be created for
  *                                          reading persistent metadata
  * @param metadataPersistenceWriteBlockSize the number of songs to be processed
  *                                          on a medium before the metadata
  *                                          file for this medium is written
  * @param excludedFileExtensions            the set with file extensions (in upper case)
  *                                          to be excluded when scanning media files
  * @param includedFileExtensions            the set with file extensions (in upper case)
  *                                          to be included when scanning media files
  * @param rootPath                          the root path to be scanned for media files
  * @param processorCount                    the number of parallel processor actors for
  *                                          metadata extraction
  * @param contentFile                       the optional path where to store
  *                                          the archive's table of content
  * @param archiveName                       a name for this archive
  * @param infoParserTimeout                 timeout for the parsing of a medium description
  *                                          file
  * @param scanMediaBufferSize               the buffer for media during scanning of an
  *                                          archive directory
  */
case class MediaArchiveConfig private[config](downloadConfig: DownloadConfig,
                                              metadataReadChunkSize: Int,
                                              infoSizeLimit: Int,
                                              tagSizeLimit: Int,
                                              processingTimeout: Timeout,
                                              metadataMediaBufferSize: Int,
                                              metadataPersistencePath: Path,
                                              metadataPersistenceChunkSize: Int,
                                              metadataPersistenceParallelCount: Int,
                                              metadataPersistenceWriteBlockSize: Int,
                                              excludedFileExtensions: Set[String],
                                              includedFileExtensions: Set[String],
                                              rootPath: Path,
                                              processorCount: Int,
                                              contentFile: Option[Path],
                                              archiveName: String,
                                              infoParserTimeout: Timeout,
                                              scanMediaBufferSize: Int)
