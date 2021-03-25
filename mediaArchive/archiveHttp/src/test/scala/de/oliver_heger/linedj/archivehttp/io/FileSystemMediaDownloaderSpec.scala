/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.core.delegate.ExtensibleFileSystem
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.{FileSystem, Model}
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object FileSystemMediaDownloaderSpec {
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
}

/**
  * Test class for ''FileSystemMediaDownloader''.
  */
class FileSystemMediaDownloaderSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers
  with MockitoSugar with AsyncTestHelper {

  import FileSystemMediaDownloaderSpec._

  "FileSystemMediaDownloader" should "resolve and download a URI from the configured file system" in {
    val DownloadUri = Uri("https://archive.example.org/path/to/file.mp3")
    val FileSource = fileSource()
    val entity = HttpEntity(ContentTypes.`application/octet-stream`, FileTestHelper.TestData.length, FileSource)
    val helper = new DownloaderTestHelper

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(DownloadUri.path.toString())).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(DownloadUri, FileSource)
  }

  it should "strip the root prefix from a URL" in {
    val RelativePath = "/my-album/my-song.mp3"
    val DownloadUri = Uri(s"https://archive.example.org$RootPath$RelativePath")
    val FileSource = fileSource()
    val entity = HttpEntity(ContentTypes.`application/octet-stream`, FileTestHelper.TestData.length, FileSource)
    val helper = new DownloaderTestHelper

    helper.prepareFileSystem { fs =>
      when(fs.resolvePath(RelativePath)).thenReturn(helper.stubOperation(FileID))
      doReturn(helper.stubOperation(entity)).when(fs).downloadFile(FileID)
    }.invokeDownloader(DownloadUri, FileSource)
  }

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class DownloaderTestHelper {
    /**
      * A test HTTP sender actor which is required for interactions with a file
      * system. Note that this actor is not actually invoked; hence the URI
      * passed to it is irrelevant.
      */
    private val httpSender = testKit.spawn(HttpRequestSender("http://www.example.org"))

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
    def prepareFileSystem(init: FileSystem[String, _, _, _] => Unit): DownloaderTestHelper = {
      init(mockFileSystem)
      this
    }

    /**
      * Returns a stub operation that yields the value provided.
      *
      * @param result the result value for the operation
      * @tparam A the type of the result
      * @return the stub operation yielding this result
      */
    def stubOperation[A](result: A): FileSystem.Operation[A] = FileSystem.Operation { sender =>
      sender should be(httpSender)
      Future.successful(result)
    }

    /**
      * Invokes the test downloader instance with the given URI and checks
      * whether the expected result is returned.
      *
      * @param uri       the URI to request
      * @param expResult the expected result
      * @return this test helper
      */
    def invokeDownloader(uri: Uri, expResult: Source[ByteString, Any]): DownloaderTestHelper = {
      futureResult(downloader.downloadMediaFile(uri)) should be(expResult)
      this
    }

    /**
      * Creates the downloader object for the current test case.
      *
      * @return the test downloader
      */
    private def createDownloader(): FileSystemMediaDownloader[String] = {
      val httpArchiveFileSystem = HttpArchiveFileSystem(mockFileSystem, RootPath, "content.json")
      new FileSystemMediaDownloader(httpArchiveFileSystem, httpSender)
    }
  }

}
