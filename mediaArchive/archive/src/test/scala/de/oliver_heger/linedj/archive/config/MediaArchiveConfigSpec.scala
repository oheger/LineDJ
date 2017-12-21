/*
 * Copyright 2015-2017 The Developers Team.
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

  /** Test root object. */
  private val MusicRoot = MediaArchiveConfig.MediaRootData(Paths get "music1", 3, None)

  /** Another root object. */
  private val CDRomRoot = MediaArchiveConfig.MediaRootData(Paths get "cdrom", 1, Some(1))

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

  /** Test prefix for URIs in the archive ToC. */
  private val TocRootPrefix = "/remote/archive"

  /** Test prefix for meta data files. */
  private val TocMetaDataPrefix = "/metadata"

  /** The archive name as returned by the name resolver function. */
  private val ArchiveName = "MyTestArchive"

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
    config.addProperty("media.roots.root.path", MusicRoot.rootPath.toString)
    config.addProperty("media.roots.root.processorCount", MusicRoot.processorCount)
    config.addProperty("media.roots.root(-1).path", CDRomRoot.rootPath.toString)
    config.addProperty("media.roots.root.processorCount", CDRomRoot.processorCount)
    config.addProperty("media.roots.root.accessRestriction", CDRomRoot.accessRestriction.get)
    config.addProperty("media.excludedExtensions", Array("JPG", "pdf", "tex"))
    config.addProperty("media.metaDataExtraction.readChunkSize", ReadChunkSize)
    config.addProperty("media.metaDataExtraction.tagSizeLimit", TagSizeLimit)
    config.addProperty("media.metaDataExtraction.processingTimeout",
      ProcessingTimeout.duration.toSeconds)
    config.addProperty("media.metaDataExtraction.metaDataUpdateChunkSize", MetaDataChunkSize)
    config.addProperty("media.metaDataExtraction.metaDataMaxMessageSize", MetaDataMaxMsgSize)
    config.addProperty("media.metaDataPersistence.path", MetaDataPersistencePath.toString)
    config.addProperty("media.metaDataPersistence.chunkSize", MetaDataPersistenceChunkSize)
    config.addProperty("media.metaDataPersistence.parallelCount", MetaDataPersistenceParallelCount)
    config.addProperty("media.metaDataPersistence.writeBlockSize",
      MetaDataPersistenceWriteBlockSize)
    config.addProperty("media.toc.file", TocFile)
    config.addProperty("media.toc.descRemovePrefix", TocRemoveDescPrefix)
    config.addProperty("media.toc.descPathSeparator", "\\")
    config.addProperty("media.toc.descUrlEncoding", true)
    config.addProperty("media.toc.rootPrefix", TocRootPrefix)
    config.addProperty("media.toc.metaDataPrefix", TocMetaDataPrefix)

    config
  }

  /**
    * Creates a ''ServerConfig'' object from a hierarchical configuration.
    *
    * @return the config object
    */
  private def createArchiveConfig(): MediaArchiveConfig =
    MediaArchiveConfig(createHierarchicalConfig(), staticArchiveName)
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

  it should "return a collection of paths with media files" in {
    val paths = createArchiveConfig().mediaRoots
    paths should contain only(MusicRoot, CDRomRoot)
  }

  it should "return a sequence of root paths" in {
    val paths = createArchiveConfig().mediaRootPaths
    paths should contain only(MusicRoot.rootPath.toString, CDRomRoot.rootPath.toString)
  }

  it should "contain a config for the archive's ToC" in {
    val tocConfig = createArchiveConfig().contentTableConfig

    tocConfig.contentFile should be(Some(Paths.get(TocFile)))
    tocConfig.descriptionRemovePrefix should be(TocRemoveDescPrefix)
    tocConfig.descriptionPathSeparator should be("\\")
    tocConfig.rootPrefix should be(Some(TocRootPrefix))
    tocConfig.metaDataPrefix should be(Some(TocMetaDataPrefix))
    tocConfig.descriptionUrlEncoding shouldBe true
  }

  it should "use default values for the ToC config" in {
    val config = createHierarchicalConfig()
    config.clearProperty("media.toc.file")
    config.clearProperty("media.toc.descRemovePrefix")
    config.clearProperty("media.toc.descPathSeparator")
    config.clearProperty("media.toc.descUrlEncoding")
    config.clearProperty("media.toc.rootPrefix")
    config.clearProperty("media.toc.metaDataPrefix")
    val tocConfig = MediaArchiveConfig(config, staticArchiveName).contentTableConfig

    tocConfig.contentFile shouldBe 'empty
    tocConfig.descriptionRemovePrefix should be(null)
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
}
