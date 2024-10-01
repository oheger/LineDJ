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

package de.oliver_heger.linedj.archive.metadata

import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.extract.id3.processor.Mp3MetadataExtractorActor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object ExtractorActorFactoryImplSpec:
  /** The tag size limit. */
  private val TagSizeLimit = 8192

  /** The read chunk size. */
  private val ReadChunkSize = 16384

/**
  * Test class for ''ExtractorActorFactoryImpl''.
  */
class ExtractorActorFactoryImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar:

  import ExtractorActorFactoryImplSpec._

  def this() = this(ActorSystem("ExtractorActorFactoryImplSpec"))

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system

  /**
    * Creates a config for the media archive that returns test values.
    *
    * @return the test config
    */
  private def createConfig(): MediaArchiveConfig =
    val config = mock[MediaArchiveConfig]
    when(config.tagSizeLimit).thenReturn(TagSizeLimit)
    when(config.metadataReadChunkSize).thenReturn(ReadChunkSize)
    config

  /**
    * Creates a factory instance from a mock configuration.
    *
    * @return the test factory instance
    */
  private def createFactory(): ExtractorActorFactoryImpl =
    new ExtractorActorFactoryImpl(createConfig())

  "An ExtractorActorFactoryImpl" should "return None for unsupported extensions" in:
    createFactory().extractorProps("txt", TestProbe().ref) should be(None)

  /**
    * Checks whether an MP3 file is correctly handled.
    *
    * @param ext the concrete extension to be used
    */
  private def checkMp3Extension(ext: String): Unit =
    val receiver = TestProbe().ref
    val expProps = Mp3MetadataExtractorActor(receiver, TagSizeLimit, ReadChunkSize)
    val factory = createFactory()

    val props = factory.extractorProps(ext, receiver)
    props.get should be(expProps)

  it should "return Props for MP3 metadata extraction" in:
    checkMp3Extension("mp3")

  it should "ignore case when evaluating file extensions" in:
    checkMp3Extension("mP3")
