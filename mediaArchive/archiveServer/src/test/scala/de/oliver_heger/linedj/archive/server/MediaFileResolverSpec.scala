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

package de.oliver_heger.linedj.archive.server

import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.Paths
import scala.concurrent.Future

/**
  * Test class for [[MediaFileResolver]].
  */
class MediaFileResolverSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with OptionValues with MediaFileTestSupport:
  def this() = this(ActorSystem("MediaFileResolverSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Reads all data from the given [[Source]] and returns it as a string.
    *
    * @param source the [[Source]]
    * @return a string with the data from this source
    */
  private def readSource(source: Source[ByteString, Any]): Future[String] =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink).map(_.utf8String)

  "toOptionalSource" should "map a successful future to a defined Option" in :
    val source = mock[Source[ByteString, NotUsed]]
    val futSource = Future.successful(source)

    MediaFileResolver.toOptionalSource(futSource).map: optSource =>
      optSource.value should be(source)

  it should "handle an unspecific exception" in :
    val exception = new IOException("Test exception: Error when resolving file.")
    val futSource: Future[Source[ByteString, NotUsed]] = Future.failed(exception)

    recoverToExceptionIf[IOException]:
      MediaFileResolver.toOptionalSource(futSource)
    .map: mappedException =>
      mappedException should be(exception)

  it should "handle an exception for an unresolvable file" in :
    val exception = new MediaFileResolver.UnresolvableFileException("testFileID", "A test exception")
    val futSource: Future[Source[ByteString, NotUsed]] = Future.failed(exception)

    MediaFileResolver.toOptionalSource(futSource).map: optSource =>
      optSource shouldBe empty

  "UnresolvableFileException" should "generate a standard message" in :
    val fileID = "non-resolvable-media-file"
    val exception = new MediaFileResolver.UnresolvableFileException(fileID)

    exception.fileID should be(fileID)
    exception.getMessage should include(fileID)

  "localFileResolverFunc" should "return a successful Future for a resolvable file" in :
    val mediaFileRelativePath = Paths.get("path", "to", "media", "file.mp3")
    val archive1Config = createArchiveConfigWithRootPath("archive1")
    val mediaFile = writeMediaFile(archive1Config, mediaFileRelativePath, FileTestHelper.TestData)
    val archive2Config = createArchiveConfigWithRootPath("otherArchive")
    writeMediaFile(archive2Config, mediaFileRelativePath, "some other data")
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/path/to/media/file.mp3"),
      archiveName = archive1Config.archiveName
    )

    val resolverFunc = MediaFileResolver.localFileResolverFunc(List(archive2Config, archive1Config))
    for
      source <- resolverFunc("someFileID", downloadInfo)
      data <- readSource(source)
    yield data should be(FileTestHelper.TestData)

  it should "return a correct failed Future for a file that cannot be resolved" in :
    val fileID = "non-resolvable-file-id"
    val archiveConfig = createArchiveConfigWithRootPath("musicArchive")
    val mediaFile = writeMediaFile(
      archiveConfig,
      Paths.get("medium", "artist", "album", "song.mp3"),
      "some data"
    )
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/medium/artist/album/nonExistingSong.mp3"),
      archiveName = "archive"
    )

    val resolverFunc = MediaFileResolver.localFileResolverFunc(List(archiveConfig))
    recoverToExceptionIf[MediaFileResolver.UnresolvableFileException]:
      resolverFunc(fileID, downloadInfo)
    .map: exception =>
      exception.fileID should be(fileID)

  it should "return a correct failed future for a file if the archive cannot be resolved" in :
    val fileID = "non-resolvable-archive-file-id"
    val archiveName = "NonExistingFile"
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("some/irrelevant/uri/song.mp3"),
      archiveName = archiveName
    )

    val resolverFunc = MediaFileResolver.localFileResolverFunc(List.empty)
    recoverToExceptionIf[MediaFileResolver.UnresolvableFileException]:
      resolverFunc(fileID, downloadInfo)
    .map: exception =>
      exception.fileID should be(fileID)
      exception.getMessage should include(fileID)
      exception.getMessage should include(archiveName)
