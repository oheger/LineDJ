/*
 * Copyright 2015-2020 The Developers Team.
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

package de.oliver_heger.linedj.archive.protocol.onedrive

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * Test class for ''OneDriveProtocol''.
  */
class OneDriveProtocolSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncTestHelper with FileTestHelper {
  def this() = this(ActorSystem("OneDriveProtocolSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import system.dispatcher

  /** The object to materialize streams in implicit scope. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  /** The timeout in implicit scope. */
  private implicit val requestTimeout: Timeout = Timeout(3.seconds)

  "OneDriveProtocol" should "report the correct name" in {
    val protocol = new OneDriveProtocol

    protocol.name should be("onedrive")
  }

  it should "return the correct flag for multi host support" in {
    val protocol = new OneDriveProtocol

    protocol.requiresMultiHostSupport shouldBe true
  }

  it should "generate a correct archive URI" in {
    val DriveID = "1234567890"
    val ArchivePath = "/foo/bar"
    val protocol = new OneDriveProtocol
    val ExpectedUri = OneDriveProtocol.OneDriveServerUri.toString() + OneDriveProtocol.OneDriveApiPath +
      DriveID + "/items/root:" + ArchivePath

    val archiveUri = protocol.generateArchiveUri(DriveID + ArchivePath).get
    archiveUri.toString() should be(ExpectedUri)
  }

  it should "handle an archive URI pointing to the root path" in {
    val DriveID = "abcdefg"
    val protocol = new OneDriveProtocol
    val ExpectedUri = OneDriveProtocol.OneDriveServerUri.toString() + OneDriveProtocol.OneDriveApiPath +
      DriveID + "/items/root:"

    val archiveUri = protocol.generateArchiveUri(DriveID + "/").get
    archiveUri.toString() should be(ExpectedUri)
  }

  it should "handle an archive URI not consisting of a drive ID and a path" in {
    val protocol = new OneDriveProtocol

    protocol.generateArchiveUri("foo") match {
      case Failure(exception) =>
        exception shouldBe a[IllegalArgumentException]
      case Success(value) =>
        fail("Unexpected result: " + value)
    }
  }

  it should "remove a trailing slash from the path of the archive URI" in {
    val DriveID = "1234567890"
    val ArchivePath = "/foo/bar"
    val protocol = new OneDriveProtocol
    val ExpectedUri = OneDriveProtocol.OneDriveServerUri.toString() + OneDriveProtocol.OneDriveApiPath +
      DriveID + "/items/root:" + ArchivePath

    val archiveUri = protocol.generateArchiveUri(DriveID + ArchivePath + "/").get
    archiveUri.toString() should be(ExpectedUri)
  }

  /**
    * Checks whether a request to download a file is handled correctly. Expects
    * that a request for the content of a file is sent and that the response is
    * handled by triggering the actual download.
    *
    * @param fileUri         the URI of the file requested
    * @param expContentUri   the expected URI of the content request
    * @param contentResponse a basic content response
    */
  private def checkDownloadRequest(fileUri: Uri, expContentUri: String, contentResponse: HttpResponse): Unit = {
    val DownloadUri = Uri("https://downloads.com/my/download/file.mp3")
    val absoluteFileUri = fileUri.resolvedAgainst(OneDriveProtocol.OneDriveServerUri)
    val ContentRequest = HttpRequest(uri = expContentUri,
      headers = OneDriveProtocol.ContentHeaders)
    val finalContentResponse = contentResponse.copy(headers = List(Location(DownloadUri)))
    val DownloadRequest = HttpRequest(uri = DownloadUri)
    val DownloadResponse = HttpResponse(status = StatusCodes.Accepted)
    val requestActor = system.actorOf(RequestActorTestImpl())
    RequestActorTestImpl.expectRequest(requestActor, ContentRequest, finalContentResponse)
    RequestActorTestImpl.expectRequest(requestActor, DownloadRequest, DownloadResponse)
    val protocol = new OneDriveProtocol

    val responseData = futureResult(protocol.downloadMediaFile(requestActor, absoluteFileUri))
    responseData.response should be(DownloadResponse)
  }

  it should "handle a download request" in {
    val FilePath = "/v1.0/me/drives/drive-id/items/root:/my/data/file.mp3"
    val FileUri = Uri(FilePath)

    checkDownloadRequest(FileUri, OneDriveProtocol.OneDriveServerUri.toString() +
      FilePath + ":/content", HttpResponse(status = StatusCodes.MovedPermanently))
  }

  it should "discard the entity bytes of the content response" in {
    val FileUri = Uri("/file.mp3")
    val entity = mock[ResponseEntity]
    val discardedEntity = new HttpMessage.DiscardedEntity(Future.successful(Done))
    when(entity.discardBytes()(mat)).thenReturn(discardedEntity)
    val ContentResponse = HttpResponse(status = StatusCodes.MovedPermanently, entity = entity)

    checkDownloadRequest(FileUri, OneDriveProtocol.OneDriveServerUri.toString() + FileUri + ":/content",
      ContentResponse)
    verify(entity).discardBytes()(mat)
  }

  it should "create a correct resolve controller" in {
    val UriToResolve = Uri("https://resolve.me.org/correctly/ok.txt")
    val BasePath = "/a/base/path"
    val protocol = new OneDriveProtocol

    val controller = protocol.resolveController(UriToResolve, BasePath).asInstanceOf[OneDriveResolverController]
    controller.uriToResolve should be(UriToResolve)
    controller.basePath should be(BasePath)
  }
}
