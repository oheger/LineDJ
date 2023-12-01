/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.io

import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.{FileSystem, Model}
import de.oliver_heger.linedj.test.{ActorTestKitSupport, AsyncTestHelper, FileTestHelper}
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.mockito.Mockito.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object FileSystemMediaDownloaderSpec:
  /** The ID of the test file to be loaded. */
  private val FileID = "fileToDownload"

  /** The root path of the file system. */
  private val RootPath = "/music/archive"

  /**
    * Generates a source that reflects the content of the file to download.
    *
    * @return the test source
    */
  private def fileSource(): Source[ByteString, Any] =
    Source.single(ByteString(FileTestHelper.TestData))

  /**
    * Returns an HTTP entity for the response of a download request that
    * produces the given source.
    *
    * @param source the ''Source'' with the data of the resulting entity
    * @return the entity
    */
  private def downloadEntity(source: Source[ByteString, Any]): HttpEntity =
    HttpEntity(ContentTypes.`application/octet-stream`, FileTestHelper.TestData.length, source)

/**
  * Test class for ''FileSystemMediaDownloader''.
  */
class FileSystemMediaDownloaderSpec extends AnyFlatSpec with Matchers with MockitoSugar with ActorTestKitSupport
  with AsyncTestHelper:

  import FileSystemMediaDownloaderSpec.*

  "FileSystemMediaDownloader" should "download a media file specified by a path" in:
    val RelativePath = Uri.Path("path/to/file.mp3")
    val FileSource = fileSource()
    val entity = downloadEntity(FileSource)
    val helper = new DownloaderTestHelper

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(RelativePath.toString())).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(RelativePath, None, FileSource)

  it should "download a media file specified by a path and a segment" in:
    val PathPrefix = Uri.Path("media")
    val Segment = "/Rock/artist/album/song.mp3"
    val FileSource = fileSource()
    val entity = downloadEntity(FileSource)
    val helper = new DownloaderTestHelper

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(s"$PathPrefix$Segment")).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(PathPrefix, Some(Segment), FileSource)

  it should "download a media file specified by a path and a segment if the path ends with a slash" in:
    val PathPrefix = Uri.Path("media/")
    val Segment = "Rock/artist/album/song.mp3"
    val FileSource = fileSource()
    val entity = downloadEntity(FileSource)
    val helper = new DownloaderTestHelper

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(s"$PathPrefix$Segment")).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(PathPrefix, Some(Segment), FileSource)

  it should "download a media file specified by a path and a segment if there are trailing and leading slashes" in:
    val PathPrefix = Uri.Path("media/")
    val Segment = "/Rock/artist/album/song.mp3"
    val FileSource = fileSource()
    val entity = downloadEntity(FileSource)
    val helper = new DownloaderTestHelper

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(s"$PathPrefix${Segment.drop(1)}")).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(PathPrefix, Some(Segment), FileSource)

  it should "download a media file specified by a path and a segment if there are no slashes as separators" in:
    val PathPrefix = Uri.Path("media")
    val Segment = "Rock/artist/album/song.mp3"
    val FileSource = fileSource()
    val entity = downloadEntity(FileSource)
    val helper = new DownloaderTestHelper(rootPath = RootPath + "/")

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(s"$PathPrefix/$Segment")).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(PathPrefix, Some(Segment), FileSource)

  it should "stop the request actor on shutdown" in:
    val helper = new DownloaderTestHelper

    helper.shutdownDownloader()
    helper.probeHttpSender.expectMessage(HttpRequestSender.Stop)

  it should "close the file system on shutdown" in:
    val helper = new DownloaderTestHelper

    helper.shutdownDownloader()
      .expectFileSystemClosed()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  /**
    * A test helper class managing a test instance and its dependencies.
    *
    * @param rootPath    the root path of the archive
    */
  private class DownloaderTestHelper(rootPath: String = RootPath):
    /**
      * Test probe for the HTTP sender actor which is required for interactions
      * with a file system.
      */
    val probeHttpSender: TestProbe[HttpRequestSender.HttpCommand] =
      testKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** Mock for the underlying file system. */
    private val mockFileSystem = mock[ExtensibleFileSystem[String, Model.File[String], Model.Folder[String],
      Model.FolderContent[String, Model.File[String], Model.Folder[String]]]]

    /** The downloader to be tested. */
    private val downloader = createDownloader()

    /**
      * Invokes the given function to initialize the mock file system.
      *
      * @param init the init function
      * @return this test helper
      */
    def prepareFileSystem(init: FileSystem[String, _, _, _] => Unit): DownloaderTestHelper =
      init(mockFileSystem)
      this

    /**
      * Returns a stub operation that yields the value provided.
      *
      * @param result the result value for the operation
      * @tparam A the type of the result
      * @return the stub operation yielding this result
      */
    def stubOperation[A](result: A): FileSystem.Operation[A] = FileSystem.Operation { sender =>
      sender should be(probeHttpSender.ref)
      Future.successful(result)
    }

    /**
      * Invokes the test downloader instance with the given path and an
      * optional segment and checks whether the expected result is returned.
      *
      * @param path       the path to download
      * @param optSegment the optional segment to append
      * @param expResult  the expected result
      * @return this test helper
      */
    def invokeDownloader(path: Uri.Path, optSegment: Option[String], expResult: Source[ByteString, Any]):
    DownloaderTestHelper =
      val futResult = optSegment match
        case Some(segment) => downloader.downloadMediaFile(path, segment)
        case None => downloader.downloadMediaFile(path)
      futureResult(futResult) should be(expResult)
      this

    /**
      * Invokes the ''shutdown()'' function on the test downloader.
      *
      * @return this test helper
      */
    def shutdownDownloader(): DownloaderTestHelper =
      downloader.shutdown()
      this

    /**
      * Checks whether the file system used by the downloader has been closed.
      *
      * @return this test helper
      */
    def expectFileSystemClosed(): DownloaderTestHelper =
      verify(mockFileSystem).close()
      this

    /**
      * Creates the downloader object for the current test case.
      *
      * @return the test downloader
      */
    private def createDownloader(): FileSystemMediaDownloader[String] =
      val httpArchiveFileSystem = HttpArchiveFileSystem(mockFileSystem, Uri.Path(rootPath))
      new FileSystemMediaDownloader(httpArchiveFileSystem, probeHttpSender.ref)

