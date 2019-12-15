/*
 * Copyright 2015-2019 The Developers Team.
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
import akka.http.scaladsl.model.headers.{Accept, Location}
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import akka.testkit.TestKit
import akka.util.Timeout
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol.ParseFolderResult
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
      DriveID + "/root:" + ArchivePath

    val archiveUri = protocol.generateArchiveUri(DriveID + ArchivePath).get
    archiveUri.toString() should be(ExpectedUri)
  }

  it should "handle an archive URI pointing to the root path" in {
    val DriveID = "abcdefg"
    val protocol = new OneDriveProtocol
    val ExpectedUri = OneDriveProtocol.OneDriveServerUri.toString() + OneDriveProtocol.OneDriveApiPath +
      DriveID + "/root:"

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
      DriveID + "/root:" + ArchivePath

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
    val ContentRequest = HttpRequest(uri = expContentUri)
    val finalContentResponse = contentResponse.copy(headers = List(Location(DownloadUri)))
    val DownloadRequest = HttpRequest(uri = DownloadUri)
    val DownloadResponse = HttpResponse(status = StatusCodes.Accepted)
    val requestActor = system.actorOf(RequestActorTestImpl())
    RequestActorTestImpl.expectRequest(requestActor, ContentRequest, finalContentResponse)
    RequestActorTestImpl.expectRequest(requestActor, DownloadRequest, DownloadResponse)
    val protocol = new OneDriveProtocol

    val responseData = futureResult(protocol.downloadMediaFile(requestActor, fileUri))
    responseData.response should be(DownloadResponse)
  }

  it should "handle a download request" in {
    val BasePath = "/v1.0/me/drives/drive-id"
    val FilePath = "/root:/my/data/file.mp3"
    val FileUri = Uri(BasePath + FilePath)

    checkDownloadRequest(FileUri, OneDriveProtocol.OneDriveServerUri.toString() + BasePath +
      "/items" + FilePath + ":/content", HttpResponse(status = StatusCodes.MovedPermanently))
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

  /**
    * Helper function to test the request for a folder.
    *
    * @param filePath   the requested file path
    * @param expUriPath the expected path in the URI (without :/children)
    */
  private def checkFolderRequest(filePath: String, expUriPath: String): Unit = {
    val ServerUri = "https://my-drive-server.com:8081"
    val BaseUri = Uri(ServerUri + "/base/path")
    val ExpectedUri = Uri(ServerUri + expUriPath + ":/children")
    val protocol = new OneDriveProtocol

    val request = protocol.createFolderRequest(BaseUri, filePath)
    request.method should be(HttpMethods.GET)
    request.uri should be(ExpectedUri)
    checkAcceptHeader(request)
  }

  /**
    * Checks whether a request contains the expected Accept header.
    *
    * @param request the request to e checked
    */
  private def checkAcceptHeader(request: HttpRequest): Unit = {
    val headerAccept = request.header[Accept].get
    headerAccept.mediaRanges.head.value should include("json")
  }

  it should "create a correct folder request" in {
    val FilePath = "/some/path/my/desired/folder"

    checkFolderRequest(FilePath, FilePath)
  }

  it should "create a correct folder request for paths ending on a slash" in {
    val FilePath = "/some/path/my/desired/folder"

    checkFolderRequest(FilePath + "/", FilePath)
  }

  /**
    * Creates a response object with an entity that is read from the resource
    * file specified.
    *
    * @param resource the name of the resource file
    * @return the response object with this entity
    */
  private def createResponseWithResourceEntity(resource: String): HttpResponse = {
    val path = resolveResourceFile(resource)
    val source = FileIO.fromPath(path)
    val entity = HttpEntity(ContentTypes.`application/json`, source)
    HttpResponse(entity = entity)
  }

  /**
    * Invokes the test protocol to parse a response with a folder listing. The
    * result from the protocol is returned.
    *
    * @param resource the resource file with the folder listing
    * @return the result produced by the protocol
    */
  private def parseFolderResponse(resource: String): ParseFolderResult = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    val protocol = new OneDriveProtocol
    futureResult(protocol.extractNamesFromFolderResponse(createResponseWithResourceEntity(resource)))
  }

  it should "extract the element names from the response of a folder request" in {
    val result = parseFolderResponse("oneDriveFolder.json")

    result.elements should contain only("folder (1)", "folder (2)")
    result.nextRequest should be(None)
  }

  it should "handle a folder response that requires a follow-up request" in {
    val result = parseFolderResponse("oneDriveFolderWithNextLink.json")

    result.elements should contain only("file (1).mp3", "file (2).mp3")
    val nextRequest = result.nextRequest.get.request
    nextRequest.uri.toString() should be("http://www.data.com/next-folder.json")
    checkAcceptHeader(nextRequest)
  }
}