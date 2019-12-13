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

import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model._
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.crypt.AESKeyGenerator
import de.oliver_heger.linedj.archivehttp.impl.crypt.UriResolverActor.{ResolveUri, ResolvedUri}
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol.ParseFolderResult
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object UriResolverActorSpec {
  /** The password for decrypting file names. */
  private val CryptPassword = "tiger"

  /** The key for decrypting data. */
  private val CryptKey = new AESKeyGenerator().generateKey(CryptPassword)

  /** A base path for the encrypted archive. */
  private val BasePath = "/my/encrypted/arc"

  /** The size of the resolved URIs cache. */
  private val CacheSize = 8

  /** A list with the names of elements contained in the root folder. */
  private val RootFolderNames = Future.successful(ParseFolderResult(List("Q8Xcluxx2ADWaUAtUHLurqSmvw==",
    "HLL2gCNjWKvwRnp4my1U2ex0QLKWpZs=", "uBQQYWockOWLuCROIHviFhU2XayMtps="), None))

  /** A list with the names of elements contained in the sub folder. */
  private val SubFolderNames = Future.successful(ParseFolderResult(List("Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
    "Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA=="), None))

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
    * Prepares the given mock for the HTTP protocol to return a result object
    * with the list of names for a request to a specific folder. If specified,
    * the request actor is also configured to return the correct response for
    * the request. Note: For some test cases requests can be executed in a
    * random order. To deal with this, multiple request URIs can be provided;
    * the mock is prepared to answer all of them.
    *
    * @param requestActor an ''Option'' for the HTTP request actor
    * @param protocol     the mock for the protocol
    * @param baseUris     the expected base URIs of the request
    * @param path         the path of the requested folder
    * @param futResult    a ''Future'' with the element names to be returned
    * @return a tuple with the generated folder request and response objects
    */
  private def stubFolderRequest(requestActor: Option[ActorRef], protocol: HttpArchiveProtocol, path: String,
                                futResult: Future[ParseFolderResult], baseUris: Uri*): (HttpRequest, HttpResponse) = {
    val request = HttpRequest(uri = path)
    val response = HttpResponse(entity = HttpEntity(path))
    requestActor foreach (RequestActorTestImpl.expectRequest(_, request, response))
    when(protocol.extractNamesFromFolderResponse(argEq(response))(any(), any())).thenReturn(futResult)
    baseUris foreach { baseUri =>
      when(protocol.createFolderRequest(baseUri, path)).thenReturn(request)
    }
    (request, response)
  }

  /**
    * Prepares the given request actor and protocol mock to handle requests
    * for the content of the simulated archive.
    *
    * @param reqActor an option for the request actor
    * @param protocol the mock for the protocol
    * @param baseUris the expected base URIs of the request
    * @return a list with the generated folder requests and responses
    */
  private def stubServerContent(reqActor: Option[ActorRef], protocol: HttpArchiveProtocol, baseUris: Uri*):
  List[(HttpRequest, HttpResponse)] = {
    stubFolderRequest(reqActor, protocol, serverPath("/"), RootFolderNames, baseUris: _*) ::
      stubFolderRequest(reqActor, protocol, serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"),
        SubFolderNames, baseUris: _*) :: Nil
  }

}

/**
  * Test class for ''UriResolverActor''.
  */
class UriResolverActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar with FileTestHelper {
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
    * @param protocol     the mock for the protocol
    * @return the new test actor instance
    */
  private def createResolverActor(requestActor: ActorRef, protocol: HttpArchiveProtocol): ActorRef = {
    val props = Props(classOf[UriResolverActor], requestActor, protocol, CryptKey, BasePath, CacheSize)
    system.actorOf(props)
  }

  "A UriResolverActor" should "handle a failure response from the request actor" in {
    val exception = new Exception("FAILURE")
    val request = HttpRequest(uri = "/failure")
    val reqActor = system.actorOf(RequestActorTestImpl())
    val protocol = mock[HttpArchiveProtocol]
    val resolveRequest = createRequest("/sub/subFile.txt")
    val path = serverPath("/")
    when(protocol.createFolderRequest(resolveRequest.uri, path)).thenReturn(request)
    RequestActorTestImpl.expectFailedRequest(reqActor, request, exception)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause match {
      case ex: FailedRequestException =>
        ex.cause should be(exception)
      case ex => fail("Unexpected failure: " + ex)
    }
  }

  it should "handle a failure from the protocol when parsing a folder response" in {
    val exception = new Exception("Protocol failure")
    val protocol = mock[HttpArchiveProtocol]
    val resolveRequest = createRequest("/sub/subFile.txt")
    val reqActor = system.actorOf(RequestActorTestImpl())
    stubFolderRequest(Some(reqActor), protocol, serverPath("/"), Future.failed(exception), resolveRequest.uri)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause should be(exception)
  }

  it should "handle a part that cannot be resolved" in {
    val protocol = mock[HttpArchiveProtocol]
    val reqActor = system.actorOf(RequestActorTestImpl())
    val resolveRequest = createRequest("/sub/nonExisting.txt")
    stubServerContent(Some(reqActor), protocol, resolveRequest.uri)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    val response = expectMsgType[Status.Failure]
    response.cause.getMessage should be("Cannot resolve nonExisting.txt in " +
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw=="))
  }

  /**
    * Checks a successful resolve operation using the URI specified.
    *
    * @param uri the URI to be resolved
    */
  private def checkResolveSuccessful(uri: String): Unit = {
    val protocol = mock[HttpArchiveProtocol]
    val resolveRequest = createRequest(uri)
    val reqActor = system.actorOf(RequestActorTestImpl())
    stubServerContent(Some(reqActor), protocol, resolveRequest.uri)
    val resolver = createResolverActor(reqActor, protocol)

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
    val protocol = mock[HttpArchiveProtocol]
    val resolveRequest1 = createRequest("/bar.txt")
    val resolveRequest2 = createRequest("/sub/subFile.txt")
    val resolveRequest3 = createRequest("/%73ub/anotherSubFile.dat")
    val reqActor = system.actorOf(RequestActorTestImpl())
    stubServerContent(Some(reqActor), protocol, resolveRequest1.uri, resolveRequest2.uri, resolveRequest3.uri)
    val resolver = createResolverActor(reqActor, protocol)
    resolver ! resolveRequest1
    expectMsg(ResolvedUri(serverPath("/uBQQYWockOWLuCROIHviFhU2XayMtps="), resolveRequest1.uri))

    resolver ! resolveRequest2
    expectMsg(ResolvedUri(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest2.uri))

    resolver ! resolveRequest3
    expectMsg(ResolvedUri(
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA=="),
      resolveRequest3.uri))
  }

  it should "combine requests to the same folder" in {
    val protocol = mock[HttpArchiveProtocol]
    val reqActor = system.actorOf(RequestActorTestImpl(failOnUnmatchedRequest = false))
    val resolver = createResolverActor(reqActor, protocol)
    val resolveRequest1 = createRequest("/bar.txt")
    val resolveRequest2 = createRequest("/sub/subFile.txt")
    val expResult1 = ResolvedUri(serverPath("/uBQQYWockOWLuCROIHviFhU2XayMtps="), resolveRequest1.uri)
    val expResult2 = ResolvedUri(
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest2.uri)
    val stubRequests = stubServerContent(None, protocol, resolveRequest1.uri, resolveRequest2.uri)

    resolver ! resolveRequest1
    resolver ! resolveRequest2
    stubRequests foreach (t => RequestActorTestImpl.expectRequest(reqActor, t._1, t._2))
    val results = List(expectMsgType[ResolvedUri], expectMsgType[ResolvedUri])
    results should contain only(expResult1, expResult2)
  }

  it should "handle a request with an invalid base path" in {
    val reqActor = system.actorOf(RequestActorTestImpl())
    val resolver = createResolverActor(reqActor, mock[HttpArchiveProtocol])
    val path = "/invalid/path/foo.txt"

    resolver ! ResolveUri(path)
    val errResponse = expectMsgType[akka.actor.Status.Failure]
    errResponse.cause.getMessage should include(path)
  }
}

