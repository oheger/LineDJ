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
import de.oliver_heger.linedj.archivecommon.download.DownloadConfig
import de.oliver_heger.linedj.shared.archive.media.MediaFileUri
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.io.IOException
import java.nio.file.{Files, Path, Paths}
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object MediaFileResolverSpec:
  /**
    * An archive configuration with some dummy settings. Concrete
    * configurations used by tests are created based on this object.
    */
  private val BaseMediaArchiveConfig = MediaArchiveConfig(
    metadataReadChunkSize = 8000,
    infoSizeLimit = 16000,
    tagSizeLimit = 2000,
    processingTimeout = Timeout(5.minutes),
    metadataMediaBufferSize = 32000,
    metadataPersistencePath = Paths.get("some", "metadata", "path"),
    metadataPersistenceChunkSize = 64,
    metadataPersistenceParallelCount = 1,
    excludedFileExtensions = Set.empty,
    includedFileExtensions = Set.empty,
    processorCount = 4,
    contentFile = None,
    infoParserTimeout = Timeout(2.minutes),
    scanMediaBufferSize = 50,
    blockingDispatcherName = "blocking-dispatcher",
    downloadConfig = DownloadConfig(
      downloadTimeout = 10.minutes,
      downloadCheckInterval = 15.minutes,
      downloadChunkSize = 128
    ),
    rootPath = null,
    archiveName = "TBD"
  )

  /**
    * Returns a [[MediaArchiveConfig]] with dummy settings and the given
    * parameters.
    *
    * @param root the root path of the archive
    * @param name the name of the archive
    * @return the configuration for this media archive
    */
  private def createArchiveConfig(root: Path, name: String): MediaArchiveConfig =
    BaseMediaArchiveConfig.copy(rootPath = root, archiveName = name)

  /**
    * Creates a dummy server configuration that contains the given archive
    * configurations.
    *
    * @param archiveConfigs the archive configurations to include
    * @return the server configuration
    */
  private def createServerConfig(archiveConfigs: Seq[MediaArchiveConfig]): ArchiveServerConfig =
    ArchiveServerConfig(
      serverPort = 8889,
      timeout = 3.minutes,
      archiveConfigs = archiveConfigs
    )
end MediaFileResolverSpec

/**
  * Test class for [[MediaFileResolver]].
  */
class MediaFileResolverSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AsyncFlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfterEach with Matchers with MockitoSugar with OptionValues with FileTestHelper:
  def this() = this(ActorSystem("MediaFileResolverSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  override protected def afterEach(): Unit =
    tearDownTestFile()
    super.afterEach()

  import MediaFileResolverSpec.*

  /**
    * Reads all data from the given [[Source]] and returns it as a string.
    *
    * @param source the [[Source]]
    * @return a string with the data from this source
    */
  private def readSource(source: Source[ByteString, Any]): Future[String] =
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    source.runWith(sink).map(_.utf8String)

  /**
    * Creates a file with the given content at the specified path. All parent
    * directories are created if necessary.
    *
    * @param path    the target path
    * @param content the content of the file
    * @return the path to the created file
    */
  private def writeFileAt(path: Path, content: String): Path =
    Files.createDirectories(path.getParent)
    writeFileContent(path, content)

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
    val archive1Path = Files.createDirectory(createPathInDirectory("archive1"))
    val mediaFile = writeFileAt(
      archive1Path.resolve(mediaFileRelativePath),
      FileTestHelper.TestData
    )
    val archive2Path = Files.createDirectory(createPathInDirectory("otherArchive"))
    writeFileAt(
      archive2Path.resolve(mediaFileRelativePath),
      "some other data"
    )
    val archive1Config = createArchiveConfig(archive1Path, "rightArchive")
    val archive2Config = createArchiveConfig(archive2Path, "wrongArchive")
    val downloadInfo = ArchiveModel.MediaFileDownloadInfo(
      fileUri = MediaFileUri("/path/to/media/file.mp3"),
      archiveName = "rightArchive"
    )

    val resolverFunc = MediaFileResolver.localFileResolverFunc(List(archive2Config, archive1Config))
    for
      source <- resolverFunc("someFileID", downloadInfo)
      data <- readSource(source)
    yield data should be(FileTestHelper.TestData)

  it should "return a correct failed Future for a file that cannot be resolved" in :
    val fileID = "non-resolvable-file-id"
    val archivePath = Files.createDirectory(createPathInDirectory("musicArchive"))
    val mediaFile = writeFileAt(
      archivePath.resolve(Paths.get("medium", "artist", "album", "song.mp3")),
      "some data"
    )
    val archiveConfig = createArchiveConfig(archivePath, "archive")
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
