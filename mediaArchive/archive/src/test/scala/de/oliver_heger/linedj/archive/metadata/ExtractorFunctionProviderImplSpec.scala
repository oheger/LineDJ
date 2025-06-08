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

package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.mockito.Mockito.*
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import scala.concurrent.Future

object ExtractorFunctionProviderImplSpec:
  /** The name of the test file. */
  private val TestFileName = "/testMP3id3v24.mp3"

  /** The path to the test file. */
  private val TestFilePath = Paths.get(classOf[ExtractorFunctionProviderImplSpec].getResource(TestFileName).toURI)
end ExtractorFunctionProviderImplSpec

/**
  * Test class for [[ExtractorFunctionProviderImpl]].
  */
class ExtractorFunctionProviderImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues with MockitoSugar:
  def this() = this(ActorSystem("ExtractorFunctionProviderImplSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  import ExtractorFunctionProviderImplSpec.*

  /**
    * Creates a config for the media archive that returns test values.
    *
    * @param tagSizeLimit the maximum allowed tag size
    * @return the test config
    */
  private def createConfig(tagSizeLimit: Int = 16384): MediaArchiveConfig =
    val config = mock[MediaArchiveConfig]
    when(config.tagSizeLimit).thenReturn(tagSizeLimit)
    when(config.metadataReadChunkSize).thenReturn(8192)
    config

  /**
    * Checks whether the provided metadata contains the expected values for the
    * test media file.
    *
    * @param metadata the metadata to check
    * @return the result of the check
    */
  private def checkMetadata(metadata: MediaMetadata): Assertion =
    metadata.title.value should be("Test Title")
    metadata.size should be(40322)
    metadata.checksum should be("eaa43a38a9dfa8aadd497d860c1c6c5a705a649467de592a0a57c7d9e2dd5bfe")

  /**
    * Creates a test provider and calls it for the given extension. Then 
    * invokes the resulting extractor function on the test file and returns the
    * extracted metadata.
    *
    * @param config    the configuration to use
    * @param extension the file extension to pass to the provider
    * @return a [[Future]] with the resulting metadata
    */
  private def obtainAndInvokeExtractorFunction(config: MediaArchiveConfig,
                                               extension: String = "mp3"): Future[MediaMetadata] =
    val provider = new ExtractorFunctionProviderImpl(config)
    val extractorFunc = provider.extractorFuncFor(extension).value

    extractorFunc(TestFilePath, system)

  "An ExtractorFunctionProviderImpl" should "return a correct extractor function for mp3 files" in :
    obtainAndInvokeExtractorFunction(createConfig()) map checkMetadata

  it should "return None for an unsupported file extension" in :
    val provider = new ExtractorFunctionProviderImpl(createConfig())

    provider.extractorFuncFor("foo") shouldBe empty

  it should "match the file extension in a case-insensitive way" in :
    obtainAndInvokeExtractorFunction(createConfig(), "MP3") map checkMetadata

  it should "take the tag size limit from the configuration into account" in :
    val config = createConfig(tagSizeLimit = 10)
    obtainAndInvokeExtractorFunction(config) map { metadata =>
      metadata.artist shouldBe empty
    }
    