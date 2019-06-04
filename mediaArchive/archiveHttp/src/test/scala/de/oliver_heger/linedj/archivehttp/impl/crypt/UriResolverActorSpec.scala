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

package de.oliver_heger.linedj.archivehttp.impl.crypt

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl.ExpectRequest
import de.oliver_heger.linedj.archivehttp.crypt.AESKeyGenerator
import de.oliver_heger.linedj.archivehttp.impl.crypt.UriResolverActor.{ResolveUri, ResolvedUri}
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.util.{Failure, Success}

object UriResolverActorSpec {
  /** The password for decrypting file names. */
  private val CryptPassword = "tiger"

  /** The key for decrypting data. */
  private val CryptKey = new AESKeyGenerator().generateKey(CryptPassword)

  /** A base path for the encrypted archive. */
  private val BasePath = "/my/encrypted/arc"

  /** The size of the resolved URIs cache. */
  private val CacheSize = 8

  /** Name of the file with the content of the archive's root folder. */
  private val RootContent = "root_encrypted.xml"

  /** Name of the file with the content of a sub folder. */
  private val FolderContent = "folder_encrypted.xml"

  /**
    * The accept header that must be present for all requests. Note that custom
    * header can obviously not be compared with equals; therefore, the header
    * has to be referenced directly.
    */
  private val ExpHeaders = List(Accept(MediaRange(MediaType.text("xml"))),
    UriResolverActor.HeaderDepth)

  /**
    * Determines the full path of a path relative to the encrypted archive.
    * Prepends the base path to the given path (which must start with a "/").
    *
    * @param relPath the relative path
    * @return the corresponding server path
    */
  private def serverPath(relPath: String): String = BasePath + relPath

  /**
    * Creates a request to resolve the URI with the specified path.
    *
    * @param path the path
    * @return the request to resolve this URI
    */
  private def createRequest(path: String): ResolveUri = ResolveUri(Uri(serverPath(path)))

  /**
    * Generates a HTTP request for a folder with the given path on a DAV
    * server.
    *
    * @param path the requested path
    * @return the full folder request for this path
    */
  private def folderRequest(path: String): HttpRequest =
    HttpRequest(uri = path, method = HttpMethod.custom("PROPFIND"), headers = ExpHeaders)

  /**
    * Generates the response from a DAV server for a folder request returning
    * the specified content.
    *
    * @param content the content of the response
    * @return the full response
    */
  private def folderResponse(content: String): HttpResponse =
    HttpResponse(entity = HttpEntity(content))

  /**
    * Generates a message for the test HTTP request actor to expect a folder
    * request and return the given response.
    *
    * @param path     the path of the requested folder
    * @param response the content of the response
    * @return the message to stub this request
    */
  private def stubFolderRequest(path: String, response: String): ExpectRequest =
    ExpectRequest(folderRequest(path), Success(folderResponse(response)))
}

/**
  * Test class for ''UriResolverActor''.
  */
class UriResolverActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender with FlatSpecLike
  with BeforeAndAfterAll with Matchers with FileTestHelper {
  def this() = this(ActorSystem("UriResolverActorSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import UriResolverActorSpec._

  /**
    * Creates a test actor instance with default settings.
    *
    * @param requestActor the request actor
    * @return the new test actor instance
    */
  private def createResolverActor(requestActor: ActorRef): ActorRef = {
    val props = Props(classOf[UriResolverActor], requestActor, CryptKey, BasePath, CacheSize)
    system.actorOf(props)
  }

  "A UriResolverActor" should "use a correct Depth header" in {
    UriResolverActor.HeaderDepth.value() should be("1")
  }

  it should "handle a failure response from the request actor" in {
    val exception = new Exception("FAILURE")
    val reqActor = system.actorOf(RequestActorTestImpl())
    reqActor ! ExpectRequest(folderRequest(serverPath("/")), Failure(exception))
    val resolver = createResolverActor(reqActor)

    resolver ! createRequest("/sub/subFile.txt")
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause match {
      case ex: FailedRequestException =>
        ex.cause should be(exception)
      case ex => fail("Unexpected failure: " + ex)
    }
  }

  it should "handle a part that cannot be resolved" in {
    val reqActor = system.actorOf(RequestActorTestImpl())
    reqActor ! stubFolderRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! stubFolderRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"),
      readResourceFile(FolderContent))
    val resolveRequest = createRequest("/sub/nonExisting.txt")
    val resolver = createResolverActor(reqActor)

    resolver ! resolveRequest
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause.getMessage should be("Cannot resolve nonExisting.txt in " +
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw%3d%3d"))
  }

  /**
    * Checks a successful resolve operation using the URI specified.
    *
    * @param uri the URI to be resolved
    */
  private def checkResolveSuccessful(uri: String): Unit = {
    val reqActor = system.actorOf(RequestActorTestImpl())
    reqActor ! stubFolderRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! stubFolderRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"), readResourceFile(FolderContent))
    val resolver = createResolverActor(reqActor)
    val resolveRequest = createRequest(uri)

    resolver ! resolveRequest
    expectMsg(ResolvedUri(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest.uri))
  }

  it should "resolve a URI" in {
    checkResolveSuccessful("/sub/subFile.txt")
  }

  it should "decode a URI before resolving it" in {
    checkResolveSuccessful("/%73ub/%73ub%46ile.txt")
  }

  it should "cache URIs that have already been resolved" in {
    val reqActor = system.actorOf(RequestActorTestImpl())
    reqActor ! stubFolderRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! stubFolderRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"),
      readResourceFile(FolderContent))
    val resolver = createResolverActor(reqActor)
    val resolveRequest1 = createRequest("/bar.txt")
    resolver ! resolveRequest1
    expectMsg(ResolvedUri(serverPath("/uBQQYWockOWLuCROIHviFhU2XayMtps="), resolveRequest1.uri))

    val resolveRequest2 = createRequest("/sub/subFile.txt")
    resolver ! resolveRequest2
    expectMsg(ResolvedUri(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest2.uri))

    val resolveRequest3 = createRequest("/%73ub/anotherSubFile.dat")
    resolver ! resolveRequest3
    expectMsg(ResolvedUri(
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA=="),
      resolveRequest3.uri))
  }

  it should "combine requests to the same folder" in {
    val reqActor = system.actorOf(RequestActorTestImpl(failOnUnmatchedRequest = false))
    val resolver = createResolverActor(reqActor)
    val resolveRequest1 = createRequest("/bar.txt")
    val resolveRequest2 = createRequest("/sub/subFile.txt")
    val expResult1 = ResolvedUri(serverPath("/uBQQYWockOWLuCROIHviFhU2XayMtps="), resolveRequest1.uri)
    val expResult2 = ResolvedUri(
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest2.uri)

    resolver ! resolveRequest1
    resolver ! resolveRequest2
    reqActor ! stubFolderRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! stubFolderRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"),
      readResourceFile(FolderContent))
    val results = List(expectMsgType[ResolvedUri], expectMsgType[ResolvedUri])
    results should contain only(expResult1, expResult2)
  }

  it should "handle a request with an invalid base path" in {
    val reqActor = system.actorOf(RequestActorTestImpl())
    val resolver = createResolverActor(reqActor)
    val path = "/invalid/path/foo.txt"

    resolver ! ResolveUri(path)
    val errResponse = expectMsgType[akka.actor.Status.Failure]
    errResponse.cause.getMessage should include(path)
  }
}

