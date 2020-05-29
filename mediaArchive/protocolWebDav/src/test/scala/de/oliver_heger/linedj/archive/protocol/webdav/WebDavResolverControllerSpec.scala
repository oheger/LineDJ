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

package de.oliver_heger.linedj.archive.protocol.webdav

import java.io.IOException

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.util.{Failure, Success}
import scala.xml.SAXParseException

object WebDavResolverControllerSpec {
  /** The relative path to the file to be resolved. */
  private val FilePath = "/rock/Heavy.mp3"

  /** The base path of the archive. */
  private val BasePath = "/music/media"

  /** The URI to be resolved. */
  private val ResolveUri = Uri("https://www.archive.org" + BasePath + FilePath)

  /**
    * Creates a test controller instance.
    *
    * @return the test controller
    */
  private def createController(): WebDavResolverController =
    new WebDavResolverController(ResolveUri, BasePath)
}

/**
  * Test class for ''WebDavResolverController''. Also some functionality of the
  * base trait is tested.
  */
class WebDavResolverControllerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("WebDavResolverControllerSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import WebDavResolverControllerSpec._
  import system.dispatcher

  /**
    * Helper function to check the creation of a folder request.
    *
    * @param path   the path to be requested
    * @param expUri the expected resulting URI
    */
  private def checkFolderRequest(path: String, expUri: Uri): Unit = {
    val ExpHeaders = List(Accept(MediaRange(MediaType.text("xml"))),
      DepthHeader.DefaultDepthHeader)
    val ExpRequest = HttpRequest(uri = expUri, method = HttpMethod.custom("PROPFIND"), headers = ExpHeaders)
    val controller = createController()

    controller.createFolderRequest(path) should be(ExpRequest)
  }

  "WebDaveResolverController" should "create a correct folder request" in {
    val FilePath = "/rock/"

    checkFolderRequest(FilePath, BasePath + FilePath)
  }

  it should "append a slash to the URI of a folder request" in {
    val FilePath = "/path/with/no/slash"

    checkFolderRequest(FilePath, BasePath + FilePath + "/")
  }

  /**
    * Returns a response object with an entity with the given content.
    *
    * @param content the body content
    * @return the response with this entity
    */
  private def createFolderResponse(content: String): HttpResponse = {
    val bodySource = Source(ByteString(content).grouped(32).toList)
    HttpResponse(entity = HttpEntity(ContentTypes.`text/xml(UTF-8)`, bodySource))
  }

  it should "extract the element names from a folder response" in {
    val response = createFolderResponse(readResourceFile("webDavFolder.xml"))
    val controller = createController()

    val result = futureResult(controller.extractNamesFromFolderResponse(response))
    result.elements should contain only("testMediaFile.mp3", "fileToBeTrimmed.mp3", "childFolder")
    result.nextRequest should be(None)
  }

  it should "handle an unexpected response" in {
    val response = createFolderResponse("<invalid> XML")
    val controller = createController()

    expectFailedFuture[SAXParseException](controller.extractNamesFromFolderResponse(response))
  }

  it should "return the correct path to be resolved" in {
    val controller = createController()

    controller.extractPathToResolve() should be(Success(FilePath))
  }

  it should "check whether the path to be resolved starts with the base path" in {
    val TestUri = Uri("/music/tests/foo.txt")
    val controller = new WebDavResolverController(TestUri, BasePath)

    controller.extractPathToResolve() match {
      case Failure(exception) =>
        exception shouldBe a[IOException]
        exception.getMessage should include(TestUri.toString())
      case r =>
        fail("Unexpected result: " + r)
    }
  }

  it should "construct the final result URI" in {
    val ResultPath = "/foo/bar/resolved.mp4"
    val controller = createController()

    val resultUri = controller.constructResultUri(ResultPath)
    resultUri should be(Uri(BasePath + ResultPath))
  }

  it should "not skip a resolve operation" in {
    val controller = createController()

    controller.skipResolve shouldBe false
  }
}
