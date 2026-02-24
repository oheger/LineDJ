/*
 * Copyright 2015-2026 The Developers Team.
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

package de.oliver_heger.linedj.archive.server.cloud

import de.oliver_heger.linedj.archive.cloud.{CloudArchiveConfig, CloudFileDownloader}
import de.oliver_heger.linedj.archive.cloud.auth.BasicAuthMethod
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object ContentDownloaderSpec:
  /** The path for the content document of the test archive. */
  private val ArchiveContentPath = Uri.Path("/toc/my-content.json")

  /** The base path for media of the test archive. */
  private val ArchiveMediaPath = Uri.Path("/cool/media")

  /** The path to the metadata files of the test archive. */
  private val ArchiveMetadataPath = Uri.Path("/test-archive-metadata")

  /** The configuration for the test archive. */
  private val TestArchiveConfig = CloudArchiveConfig(
    contentPath = ArchiveContentPath,
    mediaPath = ArchiveMediaPath,
    metadataPath = ArchiveMetadataPath,
    archiveBaseUri = Uri("https://archive.example.com/great-music"),
    archiveName = "Great Music",
    fileSystemFactory = null,
    authMethod = BasicAuthMethod("test-realm")
  )
end ContentDownloaderSpec

/**
  * Test class for [[ContentDownloader]].
  */
class ContentDownloaderSpec extends AnyFlatSpec, Matchers, MockitoSugar:

  import ContentDownloaderSpec.*

  /**
    * Returns a mock for a result to be returned from a downloader.
    *
    * @return the mock result source
    */
  private def createResult(): Future[Source[ByteString, Any]] = mock

  "A ContentDownloader" should "return a source for the content document" in :
    val downloader = mock[CloudFileDownloader]
    val result = createResult()
    when(downloader.downloadFile(ArchiveContentPath)).thenReturn(result)

    val contentDownloader = ContentDownloader(TestArchiveConfig, downloader)

    contentDownloader.loadContentDocument() should be(result)

  it should "return a source for the description of a medium" in :
    val MediumDescriptionPath = "nice-medium/my-description.json"
    val downloader = mock[CloudFileDownloader]
    val result = createResult()
    when(downloader.downloadFile(ArchiveMediaPath, MediumDescriptionPath)).thenReturn(result)

    val contentDownloader = ContentDownloader(TestArchiveConfig, downloader)

    contentDownloader.loadMediumDescription(MediumDescriptionPath) should be(result)

  it should "return a source for the metadata document of a medium" in :
    val MediumID = Checksums.MediumChecksum("test-medium-id")
    val downloader = mock[CloudFileDownloader]
    val result = createResult()
    when(downloader.downloadFile(ArchiveMetadataPath, MediumID.checksum + ".mdt")).thenReturn(result)

    val contentDownloader = ContentDownloader(TestArchiveConfig, downloader)

    contentDownloader.loadMediumMetadata(MediumID) should be(result)
