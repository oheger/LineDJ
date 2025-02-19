/*
 * Copyright 2015-2025 The Developers Team.
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
package de.oliver_heger.linedj.archive.config

import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

object MediaArchiveConfigSpec:
  /** The reader check interval. */
  private val ReaderCheckInterval = 10.minutes

  /** The root path of the test archive. */
  private val RootPath = Paths get "music"

  /** Test processor count value. */
  private val ProcessorCount = 3

  /** Test value for the chunk size when extracting metadata. */
  private val ReadChunkSize = 16384

  /** Test value for the chunk size for download operations. */
  private val DownloadChunkSize = 7777

  /** Test value for the size limit for ID3 tags. */
  private val TagSizeLimit = 4096

  /** Test value for the size limit for medium info files. */
  private val InfoSizeLimit = 8888

  /** Test value for the processing timeout. */
  private val ProcessingTimeout = Timeout(128.seconds)

  /** Test value for the buffer size during metadata extraction. */
  private val MetaDataMediaBufSize = 16

  /** Test value for the metadata persistence path. */
  private val MetaDataPersistencePath = Paths get "persistence"

  /** Test value for the metadata persistence chunk size. */
  private val MetaDataPersistenceChunkSize = 1024

  /** Test value for the metadata persistence parallel count. */
  private val MetaDataPersistenceParallelCount = 3

  /** Test value for the metadata persistence write block size. */
  private val MetaDataPersistenceWriteBlockSize = 40

  /** Test path to the content file of the media archive. */
  private val TocFile = "path/to/media/content.json"

  /** The archive name as returned by the name resolver function. */
  private val ArchiveName = "MyTestArchive"

  /** Test value for the info parser timeout property. */
  private val InfoParserTimeout = Timeout(5.minutes)

  /** Test value for the media buffer size property. */
  private val ScanMediaBufferSize = 11

  /**
    * The default name resolver function passed to the archive.
    *
    * @return the static archive name
    */
  private def staticArchiveName: String = ArchiveName

  /**
    * Adds test data for a local media archive to the given commons
    * configuration object under a specific key.
    *
    * @param config the configuration to add the data
    * @param key    the key of the archive
    * @param index  the index to generate unique test data
    * @return the modified configuration
    */
  private def addTestDataToConfig(config: HierarchicalConfiguration, key: String, index: Int):
  HierarchicalConfiguration =
    config.addProperty(key + ".infoSizeLimit", InfoSizeLimit + index)
    config.addProperty(key + ".downloadTimeout", 60 + index)
    config.addProperty(key + ".downloadCheckInterval", ReaderCheckInterval.toSeconds + index)
    config.addProperty(key + ".downloadChunkSize", DownloadChunkSize + index)
    config.addProperty(key + ".rootPath", RootPath.toString + index)
    config.addProperty(key + ".processorCount", ProcessorCount + index)
    config.addProperty(key + ".excludedExtensions", Array("JPG", "pdf", "te" + index))
    config.addProperty(key + ".includedExtensions", Array("MP3", "WAV" + index))
    config.addProperty(key + ".metaDataExtraction.readChunkSize", ReadChunkSize + index)
    config.addProperty(key + ".metaDataExtraction.tagSizeLimit", TagSizeLimit + index)
    config.addProperty(key + ".metaDataExtraction.processingTimeout",
      ProcessingTimeout.duration.toSeconds + index)
    config.addProperty(key + ".metaDataExtraction.metaDataMediaBufferSize", MetaDataMediaBufSize + index)
    config.addProperty(key + ".metaDataPersistence.path", MetaDataPersistencePath.toString + index)
    config.addProperty(key + ".metaDataPersistence.chunkSize", MetaDataPersistenceChunkSize + index)
    config.addProperty(key + ".metaDataPersistence.parallelCount", MetaDataPersistenceParallelCount + index)
    config.addProperty(key + ".metaDataPersistence.writeBlockSize",
      MetaDataPersistenceWriteBlockSize + index)
    config.addProperty(key + ".metaDataPersistence.tocFile", TocFile + index)
    config.addProperty(key + ".scan.parseInfoTimeout", InfoParserTimeout.duration.toSeconds + index)
    config.addProperty(key + ".scan.mediaBufferSize", ScanMediaBufferSize + index)

    config

  /**
    * Adds a new local archive element with test data to the given
    * configuration. The data is generated based on the index.
    *
    * @param config the configuration to add the data
    * @param index  the index to generate unique test data
    * @return the modified configuration
    */
  private def addArchiveToConfig(config: HierarchicalConfiguration, index: Int): HierarchicalConfiguration =
    config.addProperty("media.localArchives.localArchive(-1).archiveName", ArchiveName + index)
    addTestDataToConfig(config, "media.localArchives.localArchive", index)

  /**
    * Creates a hierarchical configuration with default test settings declaring
    * a single local media archive.
    *
    * @return the hierarchical configuration
    */
  private def createDefaultHierarchicalConfig(): HierarchicalConfiguration =
    addArchiveToConfig(new HierarchicalConfiguration, 0)

/**
  * Test class for ''MediaArchiveConfig''.
  */
class MediaArchiveConfigSpec extends AnyFlatSpec with Matchers:

  import MediaArchiveConfigSpec._

  /**
    * Convenience function to create a configuration for a media archive from
    * the given hierarchical configuration. It is expected that the
    * configuration declares a single archive only.
    *
    * @param c the underlying hierarchical configuration
    * @return the config object
    */
  private def createArchiveConfig(c: HierarchicalConfiguration = createDefaultHierarchicalConfig()):
  MediaArchiveConfig =
    val configs = MediaArchiveConfig(c, staticArchiveName)
    configs should have size 1
    configs.head

  /**
    * Checks whether the passed in configuration is equivalent to a test
    * configuration with the given index.
    *
    * @param config the configuration to check
    * @param index  the index determining the expected values
    */
  private def checkArchiveConfig(config: MediaArchiveConfig, index: Int): Unit =
    config.downloadConfig.downloadCheckInterval should be(ReaderCheckInterval + index.seconds)
    config.downloadConfig.downloadTimeout should be((60 + index).seconds)
    config.downloadConfig.downloadChunkSize should be(DownloadChunkSize + index)
    config.metadataReadChunkSize should be(ReadChunkSize + index)
    config.infoSizeLimit should be(InfoSizeLimit + index)
    config.tagSizeLimit should be(TagSizeLimit + index)
    config.processingTimeout.duration should be(ProcessingTimeout.duration + index.seconds)
    config.metadataMediaBufferSize should be(MetaDataMediaBufSize + index)
    config.metadataPersistencePath.toString should be(MetaDataPersistencePath.toString + index)
    config.metadataPersistenceChunkSize should be(MetaDataPersistenceChunkSize + index)
    config.metadataPersistenceParallelCount should be(MetaDataPersistenceParallelCount + index)
    config.metadataPersistenceWriteBlockSize should be(MetaDataPersistenceWriteBlockSize + index)
    config.excludedFileExtensions should contain only("JPG", "TE" + index, "PDF")
    config.includedFileExtensions should contain only("MP3", "WAV" + index)
    config.rootPath.toString should be(RootPath.toString + index)
    config.processorCount should be(ProcessorCount + index)
    config.infoParserTimeout.duration should be(InfoParserTimeout.duration + index.seconds)
    config.scanMediaBufferSize should be(ScanMediaBufferSize + index)
    config.contentFile should be(Some(Paths.get(TocFile + index)))

  "A MediaArchiveConfig" should "create an instance from the application config" in:
    checkArchiveConfig(createArchiveConfig(), 0)

  it should "use a default value for the media buffer size for metadata extraction" in:
    val c = createDefaultHierarchicalConfig()
    c clearProperty "media.localArchives.localArchive.metaDataExtraction.metaDataMediaBufferSize"

    createArchiveConfig(c).metadataMediaBufferSize should be(MediaArchiveConfig
      .DefaultMetaDataMediaBufferSize)

  it should "use a default processor count if undefined" in:
    val c = createDefaultHierarchicalConfig()
    c.clearProperty("media.localArchives.localArchive.processorCount")

    createArchiveConfig(c).processorCount should be(MediaArchiveConfig.DefaultProcessorCount)

  it should "use a default value for the ToC file" in:
    val config = createDefaultHierarchicalConfig()
    config.clearProperty("media.localArchives.localArchive.metaDataPersistence.tocFile")
    val archiveConfig = createArchiveConfig(config)

    archiveConfig.contentFile shouldBe empty

  it should "generate a default archive name using the resolver function" in:
    val appConfig = createDefaultHierarchicalConfig()
    appConfig.clearProperty("media.localArchives.localArchive.archiveName")
    val config = createArchiveConfig(appConfig)

    config.archiveName should be(ArchiveName + MediaArchiveConfig.DefaultNameSuffix)

  it should "support a static name pattern without calling the resolver function" in:
    val count = new AtomicInteger
    val appConfig = createDefaultHierarchicalConfig()
    val config = MediaArchiveConfig(appConfig, "test" + count.incrementAndGet()).head

    config.archiveName should be(ArchiveName + 0)
    count.get() should be(0)

  it should "handle exceptions thrown by the name resolver" in:
    def nameCrashed: String = throw new IllegalStateException("No name")

    val appConfig = createDefaultHierarchicalConfig()
    appConfig.clearProperty("media.localArchives.localArchive.archiveName")

    val config = MediaArchiveConfig(appConfig, nameCrashed).head
    config.archiveName should be(MediaArchiveConfig.DefaultNamePattern)

  it should "use a correct default value for the info parser timeout" in:
    val c = createDefaultHierarchicalConfig()
    c.clearProperty("media.localArchives.localArchive.scan.parseInfoTimeout")
    val config = createArchiveConfig(c)

    config.infoParserTimeout should be(MediaArchiveConfig.DefaultInfoParserTimeout)

  it should "use a correct default value for the media buffer size" in:
    val c = createDefaultHierarchicalConfig()
    c.clearProperty("media.localArchives.localArchive.scan.mediaBufferSize")
    val config = createArchiveConfig(c)

    config.scanMediaBufferSize should be(MediaArchiveConfig.DefaultScanMediaBufferSize)

  it should "read multiple archive configurations" in:
    val appConfig = addArchiveToConfig(addArchiveToConfig(createDefaultHierarchicalConfig(), 1), 2)
    val configs = MediaArchiveConfig(appConfig, staticArchiveName)

    configs should have size 3
    configs.zipWithIndex foreach { t =>
      checkArchiveConfig(t._1, t._2)
    }

  it should "support default values for all archive configurations" in:
    val appConfig = new HierarchicalConfiguration
    addTestDataToConfig(appConfig, "media.localArchives", 42)
    appConfig.addProperty("media.localArchives.localArchive.rootPath", RootPath.toString)
    appConfig.addProperty("media.localArchives.localArchive.archiveName", ArchiveName)
    addArchiveToConfig(appConfig, 2)

    val configs = MediaArchiveConfig(appConfig, staticArchiveName)
    configs should have size 2
    val config1 = configs.head
    config1.archiveName should be(ArchiveName)
    config1.rootPath should be(RootPath)
    checkArchiveConfig(config1.copy(archiveName = ArchiveName + 42,
      rootPath = Paths.get(RootPath.toString + 42)), 42)
    checkArchiveConfig(configs(1), 2)
