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

package de.oliver_heger.linedj.archive.protocol.onedrive

import java.io.IOException

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model._
import akka.stream.scaladsl.FileIO
import akka.testkit.TestKit
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController.ParseFolderResult
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success}

object OneDriveResolverControllerSpec {
  /** The path with OneDrive API prefix. */
  private val ApiPath = "/v1.0/me/drives/drive-id"

  /** The root path in OneDrive of the simulated archive. */
  private val RootPath = "/my/data"

  /** The base path used by tests. */
  private val BasePath = ApiPath + "/items/root:" + RootPath

  /** URI of a test file to be resolved. */
  private val FilePath = "/music/rock/song.mp3"

  /** The test URI that should be resolved. */
  private val UriToResolve = oneDriveUri(BasePath + FilePath + ":/content")

  /**
    * Generates an absolute OneDrive URI with the given relative path.
    *
    * @param path the path
    * @return the resulting OneDrive URI
    */
  private def oneDriveUri(path: String): Uri =
    OneDriveProtocol.OneDriveServerUri.withPath(Path(path))

  /**
    * Creates a test controller instance with default settings.
    *
    * @return the test controller instance
    */
  private def createController(): OneDriveResolverController =
    new OneDriveResolverController(UriToResolve, BasePath)
}

/**
  * Test class for ''OneDriveResolverController''.
  */
class OneDriveResolverControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("OneDriveResolverControllerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import OneDriveResolverControllerSpec._
  import system.dispatcher

  "A OneDriveResolverController" should "skip a non-content URI" in {
    val controller = new OneDriveResolverController(oneDriveUri(BasePath + "/no/content/path.txt"), BasePath)

    controller.skipResolve shouldBe true
  }

  it should "not skip a content URI" in {
    val controller = createController()

    controller.skipResolve shouldBe false
  }

  it should "extract the path to be resolved" in {
    val controller = createController()

    controller.extractPathToResolve() should be(Success(FilePath))
  }

  it should "return a Failure for the path to resolve if it does not start with the base path" in {
    val controller = new OneDriveResolverController(oneDriveUri(
      "/v1.0/me/drives/drive-id/items/root:/my/other-data" + FilePath), BasePath)

    controller.extractPathToResolve() match {
      case Failure(exception) =>
        exception shouldBe a[IOException]
      case r =>
        fail("Unexpected result for path to resolve: " + r)
    }
  }

  it should "return a Failure for the path to resolve if it does not end with the content suffix" in {
    val controller = new OneDriveResolverController(oneDriveUri(BasePath + FilePath), BasePath)

    controller.extractPathToResolve() match {
      case Failure(exception) =>
        exception shouldBe a[IOException]
      case r =>
        fail("Unexpected result for path to resolve: " + r)
    }
  }

  /**
    * Helper function to test the request for a folder.
    *
    * @param filePath   the requested file path
    * @param expUriPath the expected path in the URI (without :/children)
    */
  private def checkFolderRequest(filePath: String, expUriPath: String): Unit = {
    val ExpectedUri = oneDriveUri(ApiPath + "/root:" + RootPath + expUriPath + ":/children")
    val controller = createController()

    val request = controller.createFolderRequest(filePath)
    request.method should be(HttpMethods.GET)
    request.uri should be(ExpectedUri)
    checkAcceptHeader(request)
  }

  /**
    * Checks whether a request contains the expected Accept header.
    *
    * @param request the request to be checked
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
    val controller = createController()
    futureResult(controller.extractNamesFromFolderResponse(createResponseWithResourceEntity(resource)))
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

  it should "construct a correct result URI" in {
    val ResolvedPath = "/resolved/file/path/foo.bar"
    val ExpUri = oneDriveUri(BasePath + ResolvedPath + ":/content")
    val controller = createController()

    controller.constructResultUri(ResolvedPath) should be(ExpUri)
  }
}
