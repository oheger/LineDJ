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
import org.apache.pekko.util.Timeout

import java.nio.file.Path
import scala.collection.immutable.Seq
import scala.concurrent.duration.*

object MediaArchiveConfig:
  /**
    * Constant for the section with configuration settings about local
    * archives.
    */
  final val ArchivesSection = "media.localArchives."

  /** Constant for the key prefix under which all options are located. */
  final val ArchivesKey: String = ArchivesSection + "localArchive"

  /** The configuration property for the info size restriction. */
  final val PropInfoSizeLimit: String = "infoSizeLimit"

  /** The configuration property for the excluded file extensions. */
  final val PropExcludedExtensions: String = "excludedExtensions"

  /**
    * The configuration property for file extensions to be included when
    * scanning for media files. If this property is defined, only files with an
    * extension defined here are taken into account. Note that this property
    * takes precedence over excluded file extensions.
    */
  final val PropIncludedExtensions: String = "includedExtensions"

  /** Constant for the prefix for the metadata extraction configuration. */
  final val MetaExtractionPrefix: String = "metaDataExtraction."

  /** The configuration property for metadata extraction chunk size. */
  final val PropMetaDataReadChunkSize: String = MetaExtractionPrefix + "readChunkSize"

  /** The configuration property for the size restriction for ID3 tags. */
  final val PropTagSizeLimit: String = MetaExtractionPrefix + "tagSizeLimit"

  /** The configuration property for the processing timeout. */
  final val PropProcessingTimeout: String = MetaExtractionPrefix + "processingTimeout"

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
  final val PropMetaDataBufferSize: String = MetaExtractionPrefix + "metaDataMediaBufferSize"

  /** Constant for the prefix for the metadata persistence configuration. */
  final val MetaPersistencePrefix: String = "metaDataPersistence."

  /** The configuration property for the metadata persistence path. */
  final val PropMetaDataPersistencePath: String = MetaPersistencePrefix + "path"

  /** The configuration property for the metadata persistence chunk size. */
  final val PropMetaDataPersistenceChunkSize: String = MetaPersistencePrefix + "chunkSize"

  /** The configuration property for the metadata persistence parallel count. */
  final val PropMetaDataPersistenceParallelCount: String = MetaPersistencePrefix + "parallelCount"

  /** The configuration property for the metadata persistence write block size. */
  final val PropMetaDataPersistenceWriteBlockSize: String = MetaPersistencePrefix + "writeBlockSize"

  /**
    * Constant for the property defining the root directory of the media
    * archive. The directory structure below this path is scanned for media
    * files.
    */
  final val PropRootPath: String = "rootPath"

  /**
    * The configuration property defining the number of processors to be used
    * when extracting metadata. If the hard disc is powerful, it can make
    * sense to set a higher number to speed up metadata extraction. The
    * default value is 1.
    */
  final val PropProcessorCount: String = "processorCount"

  /**
    * The configuration property to define the archive's name. This can be a
    * string that may contain the placeholder ''${host}''. This placeholder is
    * replaced by the locale network name of the current machine. If it is
    * missing, a static name can be specified for the archive. The property is
    * optional; if it is undefined, its value is assumed to be the
    * ''${host}-media-archive''.
    */
  final val PropArchiveName: String = "archiveName"

  /**
    * The configuration property to specify the name of the blocking
    * dispatcher. This dispatcher is used for some file system operations.
    */
  final val PropBlockingDispatcherName = "blockingDispatcherName"

  /** The configuration property for the file where to store the archive ToC. */
  final val PropTocFile: String = MetaPersistencePrefix + "tocFile"

  /** Prefix for properties related to media archive scan operations. */
  final val ScanPrefix: String = "scan."

  /**
    * Configuration property for the timeout for parsing a medium description
    * file (in seconds). Medium description files (with the extension
    * ''.settings'') are parsed in parallel while the directory structure of a
    * media archive is scanned. If the parsing result does not arrive within
    * this time span, an error is assumed.
    */
  final val PropScanParseInfoTimeout: String = ScanPrefix + "parseInfoTimeout"

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
  final val PropScanMediaBufferSize: String = ScanPrefix + "mediaBufferSize"

  /**
    * Placeholder for the host name which can occur in the configured archive
    * name.
    */
  final val PlaceholderHost = "${host}"

  /**
    * Suffix for generating a default archive name. Per default, this suffix is
    * appended to the local host name to generate the archive name.
    */
  final val DefaultNameSuffix = "-media-archive"

  /**
    * The default name pattern for generating the archive name. If the property
    * for the archive name is not defined, this value is used.
    */
  final val DefaultNamePattern: String = PlaceholderHost + DefaultNameSuffix

  /** The default timeout for parsing a medium description file. */
  final val DefaultInfoParserTimeout: Timeout = Timeout(1.minute)

  /** The default value for the ''PropMetaDataBufferSize'' property. */
  final val DefaultMetaDataMediaBufferSize = 2

  /** The default value for the ''PropScanMediaBufferSize'' property. */
  final val DefaultScanMediaBufferSize = 8

  /** The default number of processors for metadata extraction. */
  final val DefaultProcessorCount = 1

  /**
    * The default number of path components to be removed from URIs of medium
    * description files when writing the archive's ToC.
    */
  final val DefaultTocDescRemovePathComponents = 0

  /** The default name of the blocking dispatcher. */
  final val DefaultBlockingDispatcherName = "blocking-dispatcher"

  /**
    * A type class interface for loading the configuration of a media archive 
    * from different configuration libraries.
    *
    * This trait defines a method for extracting information about
    * [[MediaArchiveConfig]] objects from a configuration interface. By 
    * providing different concrete instances for various configuration 
    * libraries, these libraries can be integrated and used by media archive
    * applications.
    *
    * @tparam CONFIG the type of the configuration interface to process
    */
  trait MediaArchiveConfigLoader[CONFIG]:
    /**
      * Extracts configuration information for media archives from a specific
      * configuration interface.
      *
      * @param config       the configuration object
      * @param nameResolver a function for resolving the archive names
      * @return a sequence with the archive configurations extracted
      */
    def loadMediaArchiveConfigs(config: CONFIG,
                                nameResolver: => String = LocalNameResolver.localHostName): Seq[MediaArchiveConfig]

  /**
    * Loads the configurations for media archives from the given configuration
    * object using a corresponding loader instance. This function can be used 
    * to extract media archive configurations from any configuration library 
    * for which a [[MediaArchiveConfigLoader]] exists.
    *
    * @param config       the configuration object
    * @param nameResolver a function for resolving archive names
    * @param loader       the [[MediaArchiveConfigLoader]] instance for the library
    * @tparam CONFIG the type of the configuration interface
    * @return a sequence with the archive configurations extracted
    */
  def loadMediaArchiveConfigs[CONFIG](config: CONFIG, nameResolver: => String = LocalNameResolver.localHostName)
                                     (using loader: MediaArchiveConfigLoader[CONFIG]): Seq[MediaArchiveConfig] =
    loader.loadMediaArchiveConfigs(config, nameResolver)
end MediaArchiveConfig

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
  * @param blockingDispatcherName            the name of the dispatcher for blocking
  *                                          operations
  */
case class MediaArchiveConfig(downloadConfig: DownloadConfig,
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
                              scanMediaBufferSize: Int,
                              blockingDispatcherName: String)
