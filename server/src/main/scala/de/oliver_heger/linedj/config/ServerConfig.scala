/*
 * Copyright 2015-2016 The Developers Team.
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

package de.oliver_heger.linedj.config

import java.nio.file.{Path, Paths}
import java.util.Locale
import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config, ConfigObject}
import de.oliver_heger.linedj.config.ServerConfig.MediaRootData

import scala.concurrent.duration._

object ServerConfig {
  /** Constant for the prefix for configuration options. */
  private val ConfigPrefix = "splaya.media."

  /** The configuration property for the reader timeout. */
  private val PropReaderActorTimeout = ConfigPrefix + "readerTimeout"

  /** The configuration property for the initial delay for reader timeout checks. */
  private val PropReaderCheckDelay = ConfigPrefix + "readerCheckInitialDelay"

  /** The configuration property for the interval for reader timeout checks. */
  private val PropReaderCheckInterval = ConfigPrefix + "readerCheckInterval"

  /** The configuration property for the excluded file extensions. */
  private val PropExcludedExtensions = ConfigPrefix + "excludedExtensions"

  /** Constant for the prefix for the meta data extraction configuration. */
  private val MetaExtractionPrefix = ConfigPrefix + "metaDataExtraction."

  /** The configuration property for meta data extraction chunk size. */
  private val PropMetaDataReadChunkSize = MetaExtractionPrefix + "readChunkSize"

  /** The configuration property for the size restriction for ID3 tags. */
  private val PropTagSizeLimit = MetaExtractionPrefix + "tagSizeLimit"

  /** The configuration property for the size of meta data update chunks. */
  private val PropMetaDataUpdateChunkSize = MetaExtractionPrefix + "metaDataUpdateChunkSize"

  /** The configuration property for the maximum meta data message size. */
  private val PropMetaDataMaxMessageSize = MetaExtractionPrefix + "metaDataMaxMessageSize"

  /** Constant for the prefix for the meta data persistence configuration. */
  private val MetaPersistencePrefix = ConfigPrefix + "metaDataPersistence."

  /** The configuration property for the meta data persistence path. */
  private val PropMetaDataPersistencePath = MetaPersistencePrefix + "path"

  /** The configuration property for the meta data persistence chunk size. */
  private val PropMetaDataPersistenceChunkSize = MetaPersistencePrefix + "chunkSize"

  /** The configuration property for the meta data persistence parallel count. */
  private val PropMetaDataPersistenceParallelCount = MetaPersistencePrefix + "parallelCount"

  /** Constant for the path property of a media root object. */
  private val RootPropPath = ".path"

  /** Constant for the processorCount property of a media root object. */
  private val RootPropProcessorCount = ".processorCount"

  /** Constant for the accessRestriction property of a media root object. */
  private val RootPropAccessRestriction = ".accessRestriction"

  /** Constant for a configuration key to map objects to. */
  private val ObjectKey = "Kex"

  /**
   * Creates a new instance of ''ServerConfig'' based on the passed in
   * ''Config'' object.
   * @param config the configuration to be wrapped
   * @return the new ''ServerConfig'' instance
   */
  def apply(config: Config): ServerConfig = {
    new ServerConfig(readerTimeout = durationProperty(config, PropReaderActorTimeout),
      readerCheckInterval = durationProperty(config, PropReaderCheckInterval),
      readerCheckInitialDelay = durationProperty(config, PropReaderCheckDelay),
      metaDataReadChunkSize = config getInt PropMetaDataReadChunkSize,
      tagSizeLimit = config getInt PropTagSizeLimit,
      metaDataUpdateChunkSize = config getInt PropMetaDataUpdateChunkSize,
      initMetaDataMaxMsgSize = config getInt PropMetaDataMaxMessageSize,
      metaDataPersistencePath = Paths.get(config getString PropMetaDataPersistencePath),
      metaDataPersistenceChunkSize = config getInt PropMetaDataPersistenceChunkSize,
      metaDataPersistenceParallelCount = config getInt PropMetaDataPersistenceParallelCount,
      excludedFileExtensions = obtainExcludedExtensions(config),
      rootMap = createMediaData(config))
  }

  /**
   * Reads a property of type duration from the given configuration.
   * @param config the configuration
   * @param property the property key
   * @return the duration value for this key
   */
  private def durationProperty(config: Config, property: String): FiniteDuration = {
    val millis = config.getDuration(property, TimeUnit.MILLISECONDS)
    FiniteDuration(millis, MILLISECONDS)
  }

  /**
   * Generates a map with information about media roots. The map uses root
   * paths as keys and associated ''MediaRootData'' objects as values
   * @param config the configuration
   * @return a map with information about media roots
   */
  private def createMediaData(config: Config): Map[Path, MediaRootData] = {
    import collection.JavaConversions._
    val mediaRoots = config.getObjectList(ConfigPrefix + "paths").map(createMediaRoot)
    Map(mediaRoots map (r => (r.rootPath, r)): _*)
  }

  /**
   * Creates a ''MediaRootData'' object from the given configuration
   * information.
   * @param o the configuration object
   * @return the ''MediaRootData''
   */
  private def createMediaRoot(o: ConfigObject): MediaRootData = {
    val config = o atPath ObjectKey
    MediaRootData(Paths.get(config.getString(ObjectKey + RootPropPath)),
      config.getInt(ObjectKey + RootPropProcessorCount),
      accessRestrictionProperty(config))
  }

  /**
   * Extracts the property for the access restriction of a media root from the
   * given configuration.
   * @param config the configuration
   * @return the access restriction property
   */
  private def accessRestrictionProperty(config: Config): Option[Int] = {
    if (config.hasPath(ObjectKey + RootPropAccessRestriction))
      Some(config.getInt(ObjectKey + RootPropAccessRestriction))
    else None
  }

  /**
   * Determines the set with the file extensions to be excluded.
   * @param config the configuration
   * @return the set with excluded file extensions
   */
  private def obtainExcludedExtensions(config: Config): Set[String] = {
    import collection.JavaConversions._
    config.getStringList(PropExcludedExtensions).map(_.toUpperCase(Locale.ENGLISH)).toSet
  }

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
 * @param metaDataReadChunkSize the read chunk size when extracting meta data
 * @param tagSizeLimit the size limit for ID3 tags
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
 * @param excludedFileExtensions the set with file extensions (in upper case)
 *                               to be excluded when scanning media files
 * @param rootMap a map with information about media roots
 */
class ServerConfig private[config](val readerTimeout: FiniteDuration,
                           val readerCheckInterval: FiniteDuration,
                           val readerCheckInitialDelay: FiniteDuration,
                           val metaDataReadChunkSize: Int,
                           val tagSizeLimit: Int,
                           val metaDataUpdateChunkSize: Int,
                           initMetaDataMaxMsgSize: Int,
                           val metaDataPersistencePath: Path,
                           val metaDataPersistenceChunkSize: Int,
                           val metaDataPersistenceParallelCount: Int,
                           val excludedFileExtensions: Set[String],
                           rootMap: Map[Path, MediaRootData]) {
  /** The maximum size of meta data chunk messages. */
  val metaDataMaxMessageSize: Int = calcMaxMessageSize()

  /**
   * Returns a set with paths that represent root directories for media files.
   * All media files processed by this application should be contained in one
   * of these directory structures.
   *
   * @return a set with the root paths for media files
   */
  def mediaRoots: Set[MediaRootData] = rootMap.values.toSet

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
