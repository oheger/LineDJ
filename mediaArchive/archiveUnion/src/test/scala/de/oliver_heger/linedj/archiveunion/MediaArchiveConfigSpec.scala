/*
 * Copyright 2015-2024 The Developers Team.
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

package de.oliver_heger.linedj.archiveunion

import org.apache.commons.configuration.{Configuration, PropertiesConfiguration}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

object MediaArchiveConfigSpec:
  /** Test value for the chunk size of a metadata notification. */
  private val MetadataChunkSize = 10

  /** Test value for the maximum message size of metadata chunk messages. */
  private val MetadataMaxMsgSize = 150

  /**
    * Creates a test configuration object which can be used to populate a
    * ''MediaArchiveConfig''.
    *
    * @param updateChunkSize value for the metadata update chunk size
    * @return the configuration object
    */
  private def createConfiguration(updateChunkSize: Int = MetadataChunkSize): Configuration =
    val config = new PropertiesConfiguration
    config.addProperty("media.mediaArchive.metaDataUpdateChunkSize", updateChunkSize)
    config.addProperty("media.mediaArchive.metaDataMaxMessageSize", MetadataMaxMsgSize)
    config

  /**
    * Creates a ''MediaArchiveConfig'' object initialized from a test
    * configuration.
    *
    * @param updateChunkSize value for the metadata update chunk size
    * @return the ''MediaArchiveConfig''
    */
  private def createMediaConfig(updateChunkSize: Int = MetadataChunkSize): MediaArchiveConfig =
    MediaArchiveConfig(createConfiguration(updateChunkSize))

/**
  * Test class for ''MediaArchiveConfig''.
  */
class MediaArchiveConfigSpec extends AnyFlatSpec with Matchers:

  import MediaArchiveConfigSpec._

  "A MediaArchiveConfig" should "return the correct update chunk size" in:
    createMediaConfig().metadataUpdateChunkSize should be(MetadataChunkSize)

  it should "return the correct maximum message size" in:
    createMediaConfig().metadataMaxMessageSize should be(MetadataMaxMsgSize)

  it should "adapt the maximum message size if necessary" in:
    val config = createMediaConfig(updateChunkSize = 8)

    config.metadataMaxMessageSize should be(152)
