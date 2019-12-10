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

package de.oliver_heger.linedj.archive.protocol.webdav

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model._
import akka.pattern.AskTimeoutException
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.xml.SAXParseException

/**
  * Test class for ''WebDavProtocol''. This class also tests some functionality
  * from the base trait for protocols.
  */
class WebDavProtocolSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("WebDavProtocolSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import system.dispatcher

  /** An object to materialize streams in implicit scope. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  "WebDavProtocol" should "return the correct name" in {
    val protocol = new WebDavProtocol

    protocol.name should be("webdav")
  }

  it should "return the correct multi host flag" in {
    val protocol = new WebDavProtocol

    protocol.requiresMultiHostSupport shouldBe false
  }

  it should "generate a default archive URI" in {
    val SourceUri = "https://test.archive.org/my/media"
    val protocol = new WebDavProtocol

    val archiveUri = protocol generateArchiveUri SourceUri
    archiveUri.get.toString() should be(SourceUri)
  }

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
    val protocol = new WebDavProtocol

    protocol.createFolderRequest(Uri("/some-uri"), path) should be(ExpRequest)
  }

  it should "create a correct folder request" in {
    val FilePath = "/my/test/folder/"

    checkFolderRequest(FilePath, FilePath)
  }

  it should "append a slash to the URI of a folder request" in {
    val FilePath = "/path/with/no/slash"

    checkFolderRequest(FilePath, FilePath + "/")
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
    val protocol = new WebDavProtocol

    val names = futureResult(protocol.extractNamesFromFolderResponse(response))
    names should contain only("testMediaFile.mp3", "fileToBeTrimmed.mp3", "childFolder")
  }

  it should "handle an unexpected response" in {
    val response = createFolderResponse("<invalid> XML")
    val protocol = new WebDavProtocol

    expectFailedFuture[SAXParseException](protocol.extractNamesFromFolderResponse(response))
  }

  it should "execute a download request" in {
    val SongUri = Uri("/my/nice/song.mp3")
    val ExpRequest = HttpRequest(uri = SongUri)
    val ExpResponse = HttpResponse(status = StatusCodes.Accepted)
    implicit val timeout: Timeout = Timeout(3.seconds)
    val requestActor = system.actorOf(RequestActorTestImpl())
    RequestActorTestImpl.expectRequest(requestActor, ExpRequest, ExpResponse)
    val protocol = new WebDavProtocol

    val response = futureResult(protocol.downloadMediaFile(requestActor, SongUri))
    response.response should be(ExpResponse)
  }

  it should "correctly apply the timeout to download requests" in {
    implicit val timeout: Timeout = Timeout(200.millis)
    val probeRequestActor = TestProbe()
    val protocol = new WebDavProtocol

    expectFailedFuture[AskTimeoutException](protocol.downloadMediaFile(probeRequestActor.ref, "/test.file"))
  }
}
