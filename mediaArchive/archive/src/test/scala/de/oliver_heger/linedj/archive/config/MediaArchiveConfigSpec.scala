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

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object MediaArchiveConfigSpec {
  /** The reader check interval. */
  private val ReaderCheckInterval = 10.minutes

  /** The reader check delay. */
  private val ReaderCheckDelay = 5.minutes

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

    config
  }

  /**
    * Creates a ''ServerConfig'' object from a hierarchical configuration.
    *
    * @return the config object
    */
  private def createCConfig(): MediaArchiveConfig = MediaArchiveConfig(createHierarchicalConfig())
}

/**
 * Test class for ''MediaArchiveConfig''.
 */
class MediaArchiveConfigSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
with Matchers with BeforeAndAfterAll {

  import MediaArchiveConfigSpec._

  def this() = this(ActorSystem("MediaArchiveConfigSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  "A MediaArchiveConfig" should "return the timeout for reader actors" in {
    createCConfig().downloadConfig.downloadTimeout should be(60.seconds)
  }

  it should "return the reader check interval" in {
    createCConfig().downloadConfig.downloadCheckInterval should be(ReaderCheckInterval)
  }

  it should "return the download chunk size" in {
    createCConfig().downloadConfig.downloadChunkSize should be(DownloadChunkSize)
  }

  it should "return the read chunk size" in {
    createCConfig().metaDataReadChunkSize should be(ReadChunkSize)
  }

  it should "return the info size limit" in {
    createCConfig().infoSizeLimit should be(InfoSizeLimit)
  }

  it should "return the tag size limit" in {
    createCConfig().tagSizeLimit should be(TagSizeLimit)
  }

  it should "return the processing timeout" in {
    createCConfig().processingTimeout should be(ProcessingTimeout)
  }

  it should "return the meta data chunk size" in {
    createCConfig().metaDataUpdateChunkSize should be(MetaDataChunkSize)
  }

  it should "return the maximum meta data message size" in {
    createCConfig().metaDataMaxMessageSize should be(MetaDataMaxMsgSize)
  }

  it should "return the path for meta data persistence" in {
    createCConfig().metaDataPersistencePath should be(MetaDataPersistencePath)
  }

  it should "return the meta data persistence chunk size" in {
    createCConfig().metaDataPersistenceChunkSize should be(MetaDataPersistenceChunkSize)
  }

  it should "return the meta data persistence parallel count" in {
    createCConfig().metaDataPersistenceParallelCount should be(MetaDataPersistenceParallelCount)
  }

  it should "return the meta data persistence write block size" in {
    createCConfig().metaDataPersistenceWriteBlockSize should be(MetaDataPersistenceWriteBlockSize)
  }

  it should "return the file extensions to be excluded" in {
    createCConfig().excludedFileExtensions should contain only("JPG", "TEX", "PDF")
  }

  it should "return a collection of paths with media files" in {
    val paths = createCConfig().mediaRoots
    paths should contain only(MusicRoot, CDRomRoot)
  }

  it should "return a sequence of root paths" in {
    val paths = createCConfig().mediaRootPaths
    paths should contain only(MusicRoot.rootPath.toString, CDRomRoot.rootPath.toString)
  }
}
