/*
 * Copyright 2015-2019 The Developers Team.
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

import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

import akka.util.Timeout
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

object MediaArchiveConfigSpec {
  /** The reader check interval. */
  private val ReaderCheckInterval = 10.minutes

  /** The root path of the test archive. */
  private val RootPath = Paths get "music"

  /** Test processor count value. */
  private val ProcessorCount = 3

  /** Test value for the chunk size when extracting meta data. */
  private val ReadChunkSize = 16384

  /** Test value for the chunk size for download operations. */
  private val DownloadChunkSize = 7777

  /** Test value for the size limit for ID3 tags. */
  private val TagSizeLimit = 4096

  /** Test value for the size limit for medium info files. */
  private val InfoSizeLimit = 8888

  /** Test value for the processing timeout. */
  private val ProcessingTimeout = Timeout(128.seconds)

  /** Test value for the chunk size of a meta data notification. */
  private val MetaDataChunkSize = 10

  /** Test value for the maximum message size of meta data chunk messages. */
  private val MetaDataMaxMsgSize = 150

  /** Test value for the buffer size during meta data extraction. */
  private val MetaDataMediaBufSize = 16

  /** Test value for the meta data persistence path. */
  private val MetaDataPersistencePath = Paths get "persistence"

  /** Test value for the meta data persistence chunk size. */
  private val MetaDataPersistenceChunkSize = 1024

  /** Test value for the meta data persistence parallel count. */
  private val MetaDataPersistenceParallelCount = 3

  /** Test value for the meta data persistence write block size. */
  private val MetaDataPersistenceWriteBlockSize = 40

  /** Test path to the content file of the media archive. */
  private val TocFile = "path/to/media/content.json"

  /** Test remove prefix for medium description files. */
  private val TocRemoveDescPrefix = "C:\\data\\music\\archive\\"

  /** Test number of path components to be removed in ToC URIs. */
  private val TocRemovePathComponents = 3

  /** Test prefix for URIs in the archive ToC. */
  private val TocRootPrefix = "/remote/archive"

  /** Test prefix for meta data files. */
  private val TocMetaDataPrefix = "/metadata"

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
    * Creates a test (commons) configuration with the settings used by this
    * test class.
    *
    * @return the test configuration
    */
  private def createHierarchicalConfig(): HierarchicalConfiguration = {
    val config = new HierarchicalConfiguration
    config.addProperty("media.infoSizeLimit", InfoSizeLimit)
    config.addProperty("media.downloadTimeout", 60)
    config.addProperty("media.downloadCheckInterval", ReaderCheckInterval.toSeconds)
    config.addProperty("media.downloadChunkSize", DownloadChunkSize)
    config.addProperty("media.rootPath", RootPath.toString)
    config.addProperty("media.processorCount", ProcessorCount)
    config.addProperty("media.excludedExtensions", Array("JPG", "pdf", "tex"))
    config.addProperty("media.includedExtensions", Array("MP3", "WAV"))
    config.addProperty("media.metaDataExtraction.readChunkSize", ReadChunkSize)
    config.addProperty("media.metaDataExtraction.tagSizeLimit", TagSizeLimit)
    config.addProperty("media.metaDataExtraction.processingTimeout",
      ProcessingTimeout.duration.toSeconds)
    config.addProperty("media.metaDataExtraction.metaDataUpdateChunkSize", MetaDataChunkSize)
    config.addProperty("media.metaDataExtraction.metaDataMaxMessageSize", MetaDataMaxMsgSize)
    config.addProperty("media.metaDataExtraction.metaDataMediaBufferSize", MetaDataMediaBufSize)
    config.addProperty("media.metaDataPersistence.path", MetaDataPersistencePath.toString)
    config.addProperty("media.metaDataPersistence.chunkSize", MetaDataPersistenceChunkSize)
    config.addProperty("media.metaDataPersistence.parallelCount", MetaDataPersistenceParallelCount)
    config.addProperty("media.metaDataPersistence.writeBlockSize",
      MetaDataPersistenceWriteBlockSize)
    config.addProperty("media.toc.file", TocFile)
    config.addProperty("media.toc.descRemovePrefix", TocRemoveDescPrefix)
    config.addProperty("media.toc.descRemovePathComponents", TocRemovePathComponents)
    config.addProperty("media.toc.descPathSeparator", "\\")
    config.addProperty("media.toc.descUrlEncoding", true)
    config.addProperty("media.toc.rootPrefix", TocRootPrefix)
    config.addProperty("media.toc.metaDataPrefix", TocMetaDataPrefix)
    config.addProperty("media.scan.parseInfoTimeout", InfoParserTimeout.duration.toSeconds)
    config.addProperty("media.scan.mediaBufferSize", ScanMediaBufferSize)

    config
  }

  /**
    * Creates a ''MediaArchiveConfig'' object from a hierarchical configuration.
    *
    * @param c the underlying hierarchical configuration
    * @return the config object
    */
  private def createArchiveConfig(c: HierarchicalConfiguration = createHierarchicalConfig()):
  MediaArchiveConfig = MediaArchiveConfig(c, staticArchiveName)
}

/**
 * Test class for ''MediaArchiveConfig''.
 */
class MediaArchiveConfigSpec extends FlatSpec with Matchers {

  import MediaArchiveConfigSpec._

  "A MediaArchiveConfig" should "return the timeout for reader actors" in {
    createArchiveConfig().downloadConfig.downloadTimeout should be(60.seconds)
  }

  it should "return the reader check interval" in {
    createArchiveConfig().downloadConfig.downloadCheckInterval should be(ReaderCheckInterval)
  }

  it should "return the download chunk size" in {
    createArchiveConfig().downloadConfig.downloadChunkSize should be(DownloadChunkSize)
  }

  it should "return the read chunk size" in {
    createArchiveConfig().metaDataReadChunkSize should be(ReadChunkSize)
  }

  it should "return the info size limit" in {
    createArchiveConfig().infoSizeLimit should be(InfoSizeLimit)
  }

  it should "return the tag size limit" in {
    createArchiveConfig().tagSizeLimit should be(TagSizeLimit)
  }

  it should "return the processing timeout" in {
    createArchiveConfig().processingTimeout should be(ProcessingTimeout)
  }

  it should "return the meta data chunk size" in {
    createArchiveConfig().metaDataUpdateChunkSize should be(MetaDataChunkSize)
  }

  it should "return the maximum meta data message size" in {
    createArchiveConfig().metaDataMaxMessageSize should be(MetaDataMaxMsgSize)
  }

  it should "return the media buffer size for meta data extraction" in {
    createArchiveConfig().metaDataMediaBufferSize should be(MetaDataMediaBufSize)
  }

  it should "use a default value for the media buffer size for meta data extraction" in {
    val c = createHierarchicalConfig()
    c clearProperty MediaArchiveConfig.PropMetaDataBufferSize

    createArchiveConfig(c).metaDataMediaBufferSize should be(MediaArchiveConfig
      .DefaultMetaDataMediaBufferSize)
  }

  it should "return the path for meta data persistence" in {
    createArchiveConfig().metaDataPersistencePath should be(MetaDataPersistencePath)
  }

  it should "return the meta data persistence chunk size" in {
    createArchiveConfig().metaDataPersistenceChunkSize should be(MetaDataPersistenceChunkSize)
  }

  it should "return the meta data persistence parallel count" in {
    createArchiveConfig().metaDataPersistenceParallelCount should be(MetaDataPersistenceParallelCount)
  }

  it should "return the meta data persistence write block size" in {
    createArchiveConfig().metaDataPersistenceWriteBlockSize should be(MetaDataPersistenceWriteBlockSize)
  }

  it should "return the file extensions to be excluded" in {
    createArchiveConfig().excludedFileExtensions should contain only("JPG", "TEX", "PDF")
  }

  it should "return the file extensions to be included" in {
    createArchiveConfig().includedFileExtensions should contain only ("MP3", "WAV")
  }

  it should "return the root path of the archive" in {
    createArchiveConfig().rootPath should be(RootPath)
  }

  it should "return the processor count" in {
    createArchiveConfig().processorCount should be(ProcessorCount)
  }

  it should "use a default processor count if undefined" in {
    val c = createHierarchicalConfig()
    c.clearProperty("media.processorCount")

    createArchiveConfig(c).processorCount should be(MediaArchiveConfig.DefaultProcessorCount)
  }

  it should "contain a config for the archive's ToC" in {
    val tocConfig = createArchiveConfig().contentTableConfig

    tocConfig.contentFile should be(Some(Paths.get(TocFile)))
    tocConfig.descriptionRemovePrefix should be(TocRemoveDescPrefix)
    tocConfig.descriptionRemovePathComponents should be(TocRemovePathComponents)
    tocConfig.descriptionPathSeparator should be("\\")
    tocConfig.rootPrefix should be(Some(TocRootPrefix))
    tocConfig.metaDataPrefix should be(Some(TocMetaDataPrefix))
    tocConfig.descriptionUrlEncoding shouldBe true
  }

  it should "use default values for the ToC config" in {
    val config = createHierarchicalConfig()
    config.clearProperty("media.toc.file")
    config.clearProperty("media.toc.descRemovePrefix")
    config.clearProperty("media.toc.descRemovePathComponents")
    config.clearProperty("media.toc.descPathSeparator")
    config.clearProperty("media.toc.descUrlEncoding")
    config.clearProperty("media.toc.rootPrefix")
    config.clearProperty("media.toc.metaDataPrefix")
    val tocConfig = MediaArchiveConfig(config, staticArchiveName).contentTableConfig

    tocConfig.contentFile shouldBe 'empty
    tocConfig.descriptionRemovePrefix should be(null)
    tocConfig.pathComponentsToRemove should be(0)
    tocConfig.descriptionPathSeparator should be(null)
    tocConfig.rootPrefix shouldBe 'empty
    tocConfig.metaDataPrefix shouldBe 'empty
    tocConfig.descriptionUrlEncoding shouldBe false
  }

  it should "generate a default archive name using the resolver function" in {
    val config = createArchiveConfig()

    config.archiveName should be(ArchiveName + MediaArchiveConfig.DefaultNameSuffix)
  }

  it should "support a static name pattern without calling the resolver function" in {
    val count = new AtomicInteger
    val appConfig = createHierarchicalConfig()
    appConfig.addProperty(MediaArchiveConfig.PropArchiveName, ArchiveName)
    val config = MediaArchiveConfig(appConfig, "test" + count.incrementAndGet())

    config.archiveName should be(ArchiveName)
    count.get() should be(0)
  }

  it should "handle exceptions thrown by the name resolver" in {
    def nameCrashed: String = throw new IllegalStateException("No name")

    val config = MediaArchiveConfig(createHierarchicalConfig(), nameCrashed)
    config.archiveName should be(MediaArchiveConfig.DefaultNamePattern)
  }

  it should "return the info parser timeout" in {
    val config = createArchiveConfig()

    config.infoParserTimeout should be(InfoParserTimeout)
  }

  it should "use a correct default value for the info parser timeout" in {
    val c = createHierarchicalConfig()
    c.clearProperty("media.scan.parseInfoTimeout")
    val config = createArchiveConfig(c)

    config.infoParserTimeout should be(MediaArchiveConfig.DefaultInfoParserTimeout)
  }

  it should "return the media buffer size" in {
    val config = createArchiveConfig()

    config.scanMediaBufferSize should be(ScanMediaBufferSize)
  }

  it should "use a correct default value for the media buffer size" in {
    val c = createHierarchicalConfig()
    c.clearProperty("media.scan.mediaBufferSize")
    val config = createArchiveConfig(c)

    config.scanMediaBufferSize should be(MediaArchiveConfig.DefaultScanMediaBufferSize)
  }
}
