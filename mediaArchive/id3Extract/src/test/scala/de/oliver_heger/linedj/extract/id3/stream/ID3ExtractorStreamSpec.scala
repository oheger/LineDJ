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

package de.oliver_heger.linedj.extract.id3.stream

import de.oliver_heger.linedj.extract.id3.model.Mp3Metadata
import de.oliver_heger.linedj.extract.metadata.{MetadataProvider, MetadataVersion}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}

import java.nio.file.{Path, Paths}
import scala.concurrent.Future

/**
  * Test class for [[ID3ExtractorStream]].
  */
class ID3ExtractorStreamSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with OptionValues:
  def this() = this(ActorSystem("ID3ExtractorStreamSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Returns the path to the test file with the given (resource) name.
    *
    * @param name the name of the test file
    * @return the [[Path]] to this file
    */
  private def testFilePath(name: String): Path =
    val testFileUri = getClass.getResource(s"/$name").toURI
    Paths.get(testFileUri)

  /**
    * Invokes the function to obtain metadata for the test file with the given
    * (resource) name.
    *
    * @param name the name of the test file
    * @return a [[Future]] with the resulting metadata
    */
  private def extractMetadataForTestFile(name: String): Future[MediaMetadata] =
    ID3ExtractorStream.extractMetadata()(testFilePath(name))

  /**
    * Extracts metadata for the given test file and checks it against the given
    * expected metadata.
    *
    * @param fileName         the (resource) name of the test file
    * @param expectedMetadata an object with expected metadata
    * @return the result of the check
    */
  private def checkMetadata(fileName: String, expectedMetadata: MediaMetadata): Future[Assertion] =
    extractMetadataForTestFile(fileName) map { metadata =>
      metadata should be(expectedMetadata)
    }

  "ID3ExtractorStream" should "extract ID3v2 metadata" in :
    val expectedMetadata = MediaMetadata(
      title = Some("Test Title"),
      artist = Some("Test Artist"),
      album = Some("Test Album"),
      inceptionYear = None,
      trackNumber = Some(11),
      duration = Some(2464),
      formatDescription = Some("128 kbps"),
      size = 40322,
      checksum = "eaa43a38a9dfa8aadd497d860c1c6c5a705a649467de592a0a57c7d9e2dd5bfe"
    )

    checkMetadata("testMP3id3v24.mp3", expectedMetadata)

  it should "extract ID3v1 metadata" in :
    val expectedMetadata = MediaMetadata(
      title = Some("Test Title"),
      artist = Some("Test Artist"),
      album = Some("Test Album"),
      inceptionYear = Some(2008),
      trackNumber = Some(1),
      duration = Some(2455),
      formatDescription = Some("128 kbps"),
      size = 39416,
      checksum = "5c19eafe0846f5d074a840dc9e4a8800d7062ec87b59ad0efcd00d383328e213"
    )

    checkMetadata("testMP3id3v1.mp3", expectedMetadata)

  it should "prefer tag providers with a higher version" in :

    def createMetadataProvider(data: MediaMetadata, metaVersion: MetadataVersion): MetadataProvider =
      new MetadataProvider:
        override def version: MetadataVersion = metaVersion

        override def title: Option[String] = data.title

        override def artist: Option[String] = data.artist

        override def album: Option[String] = data.album

        override def inceptionYearString: Option[String] = data.inceptionYear.map(_.toString)

        override def trackNoString: Option[String] = data.trackNumber.map(_.toString)

    val v3Metadata = MediaMetadata(
      size = 42,
      checksum = "check",
      title = Some("v3-title")
    )
    val v2Metadata = v3Metadata.copy(artist = Some("v2-artist"), title = Some("v2-title"))
    val v1Metadata = v3Metadata.copy(album = Some("v1-album"), artist = Some("v1-artist"))
    val v1Provider = createMetadataProvider(v1Metadata, MetadataVersion(1))
    val v2Provider = createMetadataProvider(v2Metadata, MetadataVersion(2))
    val v3Provider = createMetadataProvider(v3Metadata, MetadataVersion(3))
    val mp3Data = Mp3Metadata(
      version = 2,
      layer = 2,
      sampleRate = 16000,
      minimumBitRate = 128,
      maximumBitRate = 192,
      duration = 1000
    )
    val fileInfo = FileInfoSink.FileInfo(
      size = 10000,
      hashValue = "some-hash",
      hashAlgorithm = "test-hash"
    )

    val metadata = ID3ExtractorStream.createMetadata(
      optV1Provider = Some(v1Provider),
      v2Providers = List(v2Provider, v3Provider),
      mp3Data = mp3Data,
      fileInfo = fileInfo
    )

    metadata.title should be(v3Metadata.title)
    metadata.artist should be(v2Metadata.artist)
    metadata.album should be(v1Metadata.album)

  it should "enforce the tag size limit" in :
    val path = testFilePath("testMP3id3v24.mp3")
    ID3ExtractorStream.extractMetadata(tagSizeLimit = 11)(path) map { metadata =>
      metadata.title shouldBe empty
      metadata.trackNumber.value should be(11)
    }

  it should "extract metadata from a larger audio file" in :
    val title = "Violin Sonata No.22 in A major, K.305/293d: 2. Theme. Andante grazioso — Variations 1-5 — " +
      "Variation 6. Allegro [Mozart 1778]"
    val expectedMetadata = MediaMetadata(
      title = Some(title),
      artist = Some("Anne-Sophie Mutter"),
      album = Some("Mozart: The Violin Sonatas [Mutter & Orkis], Disc 2"),
      inceptionYear = Some(2006),
      trackNumber = Some(2),
      duration = Some(593134),
      formatDescription = Some("224 kbps"),
      size = 14254530,
      checksum = "a722600a6344ae05b284c4864a6952bc68b2f26b39a9e88099423a4e420a92fe"
    )

    checkMetadata("testLongTitle_v4.mp3", expectedMetadata)
