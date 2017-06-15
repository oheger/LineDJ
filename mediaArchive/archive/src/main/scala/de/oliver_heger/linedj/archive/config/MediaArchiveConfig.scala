/*
 * Copyright 2015-2017 The Developers Team.
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

import java.nio.file.{Path, Paths}
import java.util.Locale
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig.MediaRootData
import org.apache.commons.configuration.{Configuration, HierarchicalConfiguration}

import scala.concurrent.duration._

object MediaArchiveConfig {
  /** Constant for the prefix for Commons Configuration options. */
  private val CConfigPrefix = "media."

  /** The configuration property for the reader timeout. */
  private val PropReaderActorTimeout = CConfigPrefix + "readerTimeout"

  /** The configuration property for the initial delay for reader timeout checks. */
  private val PropReaderCheckDelay = CConfigPrefix + "readerCheckInitialDelay"

  /** The configuration property for the interval for reader timeout checks. */
  private val PropReaderCheckInterval = CConfigPrefix + "readerCheckInterval"

  /** The configuration property for the download chunk size. */
  private val PropDownloadChunkSize = CConfigPrefix + "downloadChunkSize"

  /** The configuration property for the info size restriction. */
  private val PropInfoSizeLimit = CConfigPrefix + "infoSizeLimit"

  /** The configuration property for the excluded file extensions. */
  private val PropExcludedExtensions = CConfigPrefix + "excludedExtensions"

  /** Constant for the prefix for the meta data extraction configuration. */
  private val MetaExtractionPrefix = CConfigPrefix + "metaDataExtraction."

  /** The configuration property for meta data extraction chunk size. */
  private val PropMetaDataReadChunkSize = MetaExtractionPrefix + "readChunkSize"

  /** The configuration property for the size restriction for ID3 tags. */
  private val PropTagSizeLimit = MetaExtractionPrefix + "tagSizeLimit"

  /** The configuration property for the processing timeout. */
  private val PropProcessingTimeout = MetaExtractionPrefix + "processingTimeout"

  /** The configuration property for the size of meta data update chunks. */
  private val PropMetaDataUpdateChunkSize = MetaExtractionPrefix + "metaDataUpdateChunkSize"

  /** The configuration property for the maximum meta data message size. */
  private val PropMetaDataMaxMessageSize = MetaExtractionPrefix + "metaDataMaxMessageSize"

  /** Constant for the prefix for the meta data persistence configuration. */
  private val MetaPersistencePrefix = CConfigPrefix + "metaDataPersistence."

  /** The configuration property for the meta data persistence path. */
  private val PropMetaDataPersistencePath = MetaPersistencePrefix + "path"

  /** The configuration property for the meta data persistence chunk size. */
  private val PropMetaDataPersistenceChunkSize = MetaPersistencePrefix + "chunkSize"

  /** The configuration property for the meta data persistence parallel count. */
  private val PropMetaDataPersistenceParallelCount = MetaPersistencePrefix + "parallelCount"

  /** The configuration property for the meta data persistence write block size. */
  private val PropMetaDataPersistenceWriteBlockSize = MetaPersistencePrefix + "writeBlockSize"

  /** Constant for the path property of a media root object. */
  private val RootPropPath = "path"

  /** Constant for the processorCount property of a media root object. */
  private val RootPropProcessorCount = "processorCount"

  /** Constant for the accessRestriction property of a media root object. */
  private val RootPropAccessRestriction = "accessRestriction"

  /**
    * Creates a new instance of ''ServerConfig'' based on the passed in
    * ''HierarchicalConfiguration'' object.
    *
    * @param config the ''HierarchicalConfiguration'' to be processed
    * @return the new ''ServerConfig'' instance
    */
  def apply(config: HierarchicalConfiguration): MediaArchiveConfig =
  new MediaArchiveConfig(readerTimeout = durationProperty(config, PropReaderActorTimeout),
    readerCheckInterval = durationProperty(config, PropReaderCheckInterval),
    readerCheckInitialDelay = durationProperty(config, PropReaderCheckDelay),
    downloadChunkSize = config getInt PropDownloadChunkSize,
    metaDataReadChunkSize = config getInt PropMetaDataReadChunkSize,
    infoSizeLimit = config getInt PropInfoSizeLimit,
    tagSizeLimit = config getInt PropTagSizeLimit,
    processingTimeout = durationProperty(config, PropProcessingTimeout),
    metaDataUpdateChunkSize = config getInt PropMetaDataUpdateChunkSize,
    initMetaDataMaxMsgSize = config getInt PropMetaDataMaxMessageSize,
    metaDataPersistencePath = Paths.get(config getString PropMetaDataPersistencePath),
    metaDataPersistenceChunkSize = config getInt PropMetaDataPersistenceChunkSize,
    metaDataPersistenceParallelCount = config getInt PropMetaDataPersistenceParallelCount,
    metaDataPersistenceWriteBlockSize = config getInt PropMetaDataPersistenceWriteBlockSize,
    excludedFileExtensions = obtainExcludedExtensions(config),
    rootMap = createMediaData(config))

  /**
    * Determines the set with file extensions to be excluded from the given
    * Commons Configuration object.
    *
    * @param config the configuration
    * @return the set with excluded file extensions
    */
  private def obtainExcludedExtensions(config: Configuration): Set[String] =
  createExclusionSet(config getList PropExcludedExtensions)

  /**
    * Reads a property from the given configuration object and converts it to a
    * duration.
    *
    * @param config the configuration
    * @param key    the key
    * @return the resulting duration
    */
  private def durationProperty(config: Configuration, key: String): FiniteDuration =
  FiniteDuration(config getLong key, TimeUnit.SECONDS)

  /**
    * Generates a map with information about media roots from the given
    * Commons Configuration object. The map uses root paths as keys and
    * associated ''MediaRootData'' objects as values.
    *
    * @param config the configuration
    * @return a map with information about media roots
    */
  private def createMediaData(config: HierarchicalConfiguration): Map[Path, MediaRootData] = {
    import collection.JavaConversions._
    val mediaRoots = config.configurationsAt(CConfigPrefix + "roots.root")
      .map(createMediaRoot)
    createRootData(mediaRoots)
  }

  /**
    * Creates a ''MediaRootData'' object from the given Commons Configuration
    * object.
    *
    * @param config the configuration object
    * @return the ''MediaRootData''
    */
  private def createMediaRoot(config: Configuration): MediaRootData = {
    MediaRootData(Paths.get(config.getString(RootPropPath)),
      config.getInt(RootPropProcessorCount),
      accessRestrictionProperty(config))
  }

  /**
    * Extracts the property for the access restriction of a media root from the
    * given Commons Configuration object.
    *
    * @param config the configuration
    * @return the access restriction property
    */
  private def accessRestrictionProperty(config: Configuration): Option[Int] = {
    if (config containsKey RootPropAccessRestriction)
      Some(config.getInt(RootPropAccessRestriction))
    else None
  }

  /**
    * Transforms a list with file extensions to be excluded into a set.
    *
    * @param excludes the list with file extensions to be excluded
    * @return the converted set with exclusions
    */
  private def createExclusionSet(excludes: java.util.List[_]): Set[String] = {
    import collection.JavaConversions._
    excludes.map(_.toString.toUpperCase(Locale.ENGLISH)).toSet
  }

  /**
    * Creates the map with data about roots from the given sequence of root
    * data.
    *
    * @param mediaRoots the sequence of ''MediaRootData'' objects
    * @return the map with root data
    */
  private def createRootData(mediaRoots: Seq[MediaRootData]): Map[Path, MediaRootData] =
  Map(mediaRoots map (r => (r.rootPath, r)): _*)

  /**
   * A data class storing information about a media root. A media root is a
   * directory structure that contains media files. In addition to the actual
   * root path, some meta data is provided which is needed while processing
   * this structure.
   * @param rootPath the root path to the structure
   * @param processorCount the number of parallel processors during meta data
   *                       extraction
   * @param accessRestriction an optional restriction for parallel reads; for
   *                          instance, a CD-ROM drive should not be read
   *                          simultaneously by multiple threads
   */
  case class MediaRootData(rootPath: Path, processorCount: Int, accessRestriction: Option[Int])

}

/**
 * A class for managing configuration data for the server-side actor system.
 *
 * This class is a thin wrapper around the default configuration of an actor
 * system. It offers methods for accessing specific configuration properties.
 * Instances are created using a static factory method.
 *
 * @param readerTimeout the timeout for reader actors
 * @param readerCheckInterval the check interval for reader actors
 * @param readerCheckInitialDelay the initial delay for reader actor checks
 * @param downloadChunkSize chunk size for download operations
 * @param metaDataReadChunkSize the read chunk size when extracting meta data
 * @param infoSizeLimit the maximum size of a medium description file
 * @param tagSizeLimit the size limit for ID3 tags
 * @param processingTimeout a timeout for the processing of a single media
  *                         file
 * @param metaDataUpdateChunkSize the size of a chunk of meta data sent to a
 *                                registered meta data listener as an update
 *                                notification; this property determines how
 *                                often a meta data listener receives update
 *                                notifications when new meta data becomes
 *                                available
 * @param initMetaDataMaxMsgSize the maximum number of entries in a meta data
 *                               chunk message; there is a limit in the size
 *                               of remoting messages; therefore, this
 *                               parameter is important to not exceed this
 *                               limit; this value should be a multiple of the
 *                               update chunk size
 * @param metaDataPersistencePath the path used by meta data persistence; here
 *                                extracted meta data is stored in files
 * @param metaDataPersistenceChunkSize the chunk size to be used when reading
 *                                     or writing files with persistent meta
 *                                     data
 * @param metaDataPersistenceParallelCount the number of parallel reader or
 *                                         writer actors to be created for
 *                                         reading persistent meta data
 * @param metaDataPersistenceWriteBlockSize the number of songs to be processed
 *                                          on a medium before the meta data
 *                                          file for this medium is written
 * @param excludedFileExtensions the set with file extensions (in upper case)
 *                               to be excluded when scanning media files
 * @param rootMap a map with information about media roots
 */
case class MediaArchiveConfig private[config](readerTimeout: FiniteDuration,
                                              readerCheckInterval: FiniteDuration,
                                              readerCheckInitialDelay: FiniteDuration,
                                              downloadChunkSize: Int,
                                              metaDataReadChunkSize: Int,
                                              infoSizeLimit: Int,
                                              tagSizeLimit: Int,
                                              processingTimeout: Timeout,
                                              metaDataUpdateChunkSize: Int,
                                              initMetaDataMaxMsgSize: Int,
                                              metaDataPersistencePath: Path,
                                              metaDataPersistenceChunkSize: Int,
                                              metaDataPersistenceParallelCount: Int,
                                              metaDataPersistenceWriteBlockSize: Int,
                                              excludedFileExtensions: Set[String],
                                              rootMap: Map[Path, MediaRootData]) {
  /** The maximum size of meta data chunk messages. */
  val metaDataMaxMessageSize: Int = calcMaxMessageSize()

  /**
   * Returns a set with objects that represent root directories for media files.
   * All media files processed by this application should be contained in one
   * of these directory structures.
   *
   * @return a set with the root paths for media files
   */
  def mediaRoots: Set[MediaRootData] = rootMap.values.toSet

  /**
    * Returns a set with the paths under which media files are located. This is
    * a convenience method that extracts the paths from the ''MediaRootData''
    * objects.
    *
    * @return a set with paths for media files
    */
  def mediaRootPaths: Set[String] = mediaRoots map (_.rootPath.toString)

  /**
   * Returns an option for the ''MediaRootData'' object with the specified
   * root path. With this method extended information about a given root
   * path can be obtained.
   *
   * @param rootPath the root path
   * @return an option for the corresponding ''MediaRootData''
   */
  def rootFor(rootPath: Path): Option[MediaRootData] = rootMap get rootPath

  /**
    * Calculates the maximum message size based on constructor parameters. This
    * method ensures that the maximum message size is always a multiple of the
    * update chunk size. If necessary, the value is rounded upwards.
    *
    * @return the maximum meta data chunk message size
    */
  private def calcMaxMessageSize(): Int = {
    if (initMetaDataMaxMsgSize % metaDataUpdateChunkSize == 0) initMetaDataMaxMsgSize
    else (initMetaDataMaxMsgSize / metaDataUpdateChunkSize + 1) * metaDataUpdateChunkSize
  }
}
