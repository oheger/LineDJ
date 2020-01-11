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

package de.oliver_heger.linedj.archivehttp.impl.crypt

import java.io.IOException

import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model._
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.crypt.AESKeyGenerator
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.impl.crypt.UriResolverActor.{ResolveUri, ResolvedUri}
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException
import de.oliver_heger.linedj.archivehttp.spi.{HttpArchiveProtocol, UriResolverController}
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController.ParseFolderResult
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
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

  /** A list with the names of elements contained in the root folder. */
  private val RootFolderNamesList = List("Q8Xcluxx2ADWaUAtUHLurqSmvw==",
    "HLL2gCNjWKvwRnp4my1U2ex0QLKWpZs=", "uBQQYWockOWLuCROIHviFhU2XayMtps=")

  /** A list with the names of elements contained in the sub folder. */
  private val SubFolderNamesList = List("Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
    "Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA==")

  /** The result when querying the root folder. */
  private val RootFolderNames = Future.successful(ParseFolderResult(RootFolderNamesList, None))

  /** The result when querying the sub folder. */
  private val SubFolderNames = Future.successful(ParseFolderResult(SubFolderNamesList, None))

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
    * Creates a request to resolve the URI with the specified relative path and
    * prepares the mock for the controller to return this relative path as path
    * to be resolved.
    *
    * @param path       the relative path
    * @param controller the mock controller
    * @return the request to resolve this path
    */
  private def createRequestAndPreparePath(path: String, controller: UriResolverController): ResolveUri = {
    when(controller.extractPathToResolve()).thenReturn(Success(path))
    createRequest(path)
  }

  /**
    * Prepares the mock controller to return a final result URI for the given
    * resolved path. The URI is returned.
    *
    * @param resolvedPath the resolved path
    * @param controller   the mock controller
    * @return the final URI returned by the controller
    */
  private def prepareFinalUri(resolvedPath: String, controller: UriResolverController): Uri = {
    val resultUri = Uri(serverPath(resolvedPath))
    when(controller.constructResultUri(resolvedPath)).thenReturn(resultUri)
    resultUri
  }

  /**
    * Prepares the given mock for the resolver controller to return a result
    * object with the list of names for a request to a specific folder. If
    * specified, the request actor is also configured to return the correct
    * response for the request.
    *
    * @param requestActor an ''Option'' for the HTTP request actor
    * @param controller   the mock for the resolver controller
    * @param mockRequest  flag whether the request creation should be mocked
    * @param path         the path of the requested folder
    * @param futResult    a ''Future'' with the element names to be returned
    * @return a tuple with the generated folder request and response objects
    */
  private def stubFolderRequest(requestActor: Option[ActorRef], controller: UriResolverController, path: String,
                                futResult: Future[ParseFolderResult], mockRequest: Boolean):
  (HttpRequest, HttpResponse) = {
    val request = HttpRequest(uri = path)
    val response = HttpResponse(entity = HttpEntity(path))
    requestActor foreach (RequestActorTestImpl.expectRequest(_, request, response))
    when(controller.extractNamesFromFolderResponse(argEq(response))(any(), any())).thenReturn(futResult)
    if (mockRequest) {
      when(controller.createFolderRequest(path)).thenReturn(request)
    }
    (request, response)
  }

  /**
    * Prepares the given request actor and controller mock to handle requests
    * for the content of the simulated archive.
    *
    * @param reqActor   an option for the request actor
    * @param controller the mock for the resolve controller
    * @return a list with the generated folder requests and responses
    */
  private def stubServerContent(reqActor: Option[ActorRef], controller: UriResolverController):
  List[(HttpRequest, HttpResponse)] = {
    stubFolderRequest(reqActor, controller, "/", RootFolderNames, mockRequest = true) ::
      stubFolderRequest(reqActor, controller, "/" + RootFolderNamesList.head + "/",
        SubFolderNames, mockRequest = true) :: Nil
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
    val controller = mock[UriResolverController]
    val resolveRequest = createRequestAndPreparePath("/sub/subFile.txt", controller)
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    when(controller.createFolderRequest("/")).thenReturn(request)
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

  it should "handle a failure from the controller when parsing a folder response" in {
    val exception = new Exception("Protocol failure")
    val protocol = mock[HttpArchiveProtocol]
    val controller = mock[UriResolverController]
    val resolveRequest = createRequestAndPreparePath("/sub/subFile.txt", controller)
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    val reqActor = system.actorOf(RequestActorTestImpl())
    stubFolderRequest(Some(reqActor), controller, "/", Future.failed(exception), mockRequest = true)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause should be(exception)
  }

  it should "handle a part that cannot be resolved" in {
    val protocol = mock[HttpArchiveProtocol]
    val controller = mock[UriResolverController]
    val reqActor = system.actorOf(RequestActorTestImpl())
    val resolveRequest = createRequestAndPreparePath("/sub/nonExisting.txt", controller)
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    stubServerContent(Some(reqActor), controller)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    val response = expectMsgType[Status.Failure]
    response.cause.getMessage should be("Cannot resolve nonExisting.txt in /Q8Xcluxx2ADWaUAtUHLurqSmvw==")
  }

  /**
    * Checks a successful resolve operation using the URI specified.
    *
    * @param uri the URI to be resolved
    */
  private def checkResolveSuccessful(uri: String): Unit = {
    val protocol = mock[HttpArchiveProtocol]
    val controller = mock[UriResolverController]
    val resolveRequest = createRequestAndPreparePath(uri, controller)
    val ResultUri =
      prepareFinalUri("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN", controller)
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    val reqActor = system.actorOf(RequestActorTestImpl())
    stubServerContent(Some(reqActor), controller)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    expectMsg(ResolvedUri(ResultUri, resolveRequest.uri))
  }

  it should "resolve a URI" in {
    checkResolveSuccessful("/sub/subFile.txt")
  }

  it should "decode a URI before resolving it" in {
    checkResolveSuccessful("/%73ub/%73ub%46ile.txt")
  }

  it should "cache URIs that have already been resolved" in {
    val protocol = mock[HttpArchiveProtocol]
    val controller1 = mock[UriResolverController]
    val controller2 = mock[UriResolverController]
    val controller3 = mock[UriResolverController]
    val resolveRequest1 = createRequestAndPreparePath("/bar.txt", controller1)
    val resolveRequest2 = createRequestAndPreparePath("/sub/subFile.txt", controller2)
    val resolveRequest3 = createRequestAndPreparePath("/%73ub/anotherSubFile.dat", controller3)
    when(protocol.resolveController(resolveRequest1.uri, BasePath)).thenReturn(controller1)
    when(protocol.resolveController(resolveRequest2.uri, BasePath)).thenReturn(controller2)
    when(protocol.resolveController(resolveRequest3.uri, BasePath)).thenReturn(controller3)
    val ResultUri1 = prepareFinalUri("/uBQQYWockOWLuCROIHviFhU2XayMtps=", controller1)
    val ResultUri2 = prepareFinalUri("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN",
      controller2)
    val ResultUri3 =
      prepareFinalUri("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Z3BDvmY89rQwUqJ3XzMUWgtBE9bcOCYxiTq-Zfo-sNlIGA==",
        controller3)
    val reqActor = system.actorOf(RequestActorTestImpl())
    stubFolderRequest(Some(reqActor), controller1, "/", RootFolderNames, mockRequest = true)
    stubFolderRequest(Some(reqActor), controller2, "/" + RootFolderNamesList.head + "/",
      SubFolderNames, mockRequest = true)
    val resolver = createResolverActor(reqActor, protocol)
    resolver ! resolveRequest1
    expectMsg(ResolvedUri(ResultUri1, resolveRequest1.uri))

    resolver ! resolveRequest2
    expectMsg(ResolvedUri(ResultUri2, resolveRequest2.uri))

    resolver ! resolveRequest3
    expectMsg(ResolvedUri(ResultUri3, resolveRequest3.uri))
  }

  it should "combine requests to the same folder" in {
    val protocol = mock[HttpArchiveProtocol]
    val controller1 = mock[UriResolverController]
    val controller2 = mock[UriResolverController]
    val reqActor = system.actorOf(RequestActorTestImpl(failOnUnmatchedRequest = false))
    val resolver = createResolverActor(reqActor, protocol)
    val resolveRequest1 = createRequestAndPreparePath("/bar.txt", controller1)
    val resolveRequest2 = createRequestAndPreparePath("/sub/subFile.txt", controller2)
    when(protocol.resolveController(resolveRequest1.uri, BasePath)).thenReturn(controller1)
    when(protocol.resolveController(resolveRequest2.uri, BasePath)).thenReturn(controller2)
    val ResultUri1 = prepareFinalUri("/uBQQYWockOWLuCROIHviFhU2XayMtps=", controller1)
    val ResultUri2 =
      prepareFinalUri("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN", controller2)
    val expResult1 = ResolvedUri(ResultUri1, resolveRequest1.uri)
    val expResult2 = ResolvedUri(ResultUri2, resolveRequest2.uri)
    val stubRequests = stubServerContent(None, controller1)
    stubServerContent(None, controller2)

    resolver ! resolveRequest1
    resolver ! resolveRequest2
    stubRequests foreach (t => RequestActorTestImpl.expectRequest(reqActor, t._1, t._2))
    val results = List(expectMsgType[ResolvedUri], expectMsgType[ResolvedUri])
    results should contain only(expResult1, expResult2)
  }

  it should "handle a URI that is rejected by the resolve controller" in {
    val protocol = mock[HttpArchiveProtocol]
    val controller = mock[UriResolverController]
    val path = "/invalid/path/foo.txt"
    val resolveRequest = ResolveUri(path)
    val exception = new IOException("Rejected")
    when(controller.extractPathToResolve()).thenReturn(Failure(exception))
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    val reqActor = system.actorOf(RequestActorTestImpl())
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    val errResponse = expectMsgType[akka.actor.Status.Failure]
    errResponse.cause should be(exception)
  }

  it should "handle paging correctly" in {
    val protocol = mock[HttpArchiveProtocol]
    val controller = mock[UriResolverController]
    val reqActor = system.actorOf(RequestActorTestImpl(failOnUnmatchedRequest = false))
    val resolveRequest = createRequestAndPreparePath("/sub/subFile.txt", controller)
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    val (request1, response1) = stubFolderRequest(None, controller, "/path2",
      Future.successful(ParseFolderResult(List(RootFolderNamesList.head), None)), mockRequest = false)
    val (request2, response2) = stubFolderRequest(None, controller, "/path1",
      Future.successful(ParseFolderResult(List(RootFolderNamesList(1)),
        Some(HttpRequests.SendRequest(request1, null)))), mockRequest = false)
    stubFolderRequest(Some(reqActor), controller, "/",
      Future.successful(ParseFolderResult(RootFolderNamesList.drop(2),
        Some(HttpRequests.SendRequest(request2, null)))), mockRequest = true)
    RequestActorTestImpl.expectRequest(reqActor, request2, response2)
    RequestActorTestImpl.expectRequest(reqActor, request1, response1)
    stubFolderRequest(Some(reqActor), controller, "/" + RootFolderNamesList.head + "/",
      SubFolderNames, mockRequest = true)
    val ResultUri =
      prepareFinalUri("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN", controller)
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    expectMsg(ResolvedUri(ResultUri, resolveRequest.uri))
  }

  it should "return the URI unchanged if the resolve operation is to be skipped" in {
    val protocol = mock[HttpArchiveProtocol]
    val controller = mock[UriResolverController]
    when(controller.skipResolve).thenReturn(true)
    val resolveRequest = createRequest("/path/to/be/used/directly.txt")
    when(protocol.resolveController(resolveRequest.uri, BasePath)).thenReturn(controller)
    val reqActor = system.actorOf(RequestActorTestImpl())
    val resolver = createResolverActor(reqActor, protocol)

    resolver ! resolveRequest
    expectMsg(ResolvedUri(resolveRequest.uri, resolveRequest.uri))
  }
}

