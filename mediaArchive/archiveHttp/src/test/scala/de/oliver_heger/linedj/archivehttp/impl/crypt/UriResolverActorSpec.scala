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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.testkit.{ImplicitSender, TestKit}
import de.oliver_heger.linedj.FileTestHelper
import de.oliver_heger.linedj.archivehttp.crypt.AESKeyGenerator
import de.oliver_heger.linedj.archivehttp.impl.crypt.UriResolverActor.{ResolveUri, ResolvedUri}
import de.oliver_heger.linedj.archivehttp.impl.io.HttpRequestActor.{ResponseData, SendRequest}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

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

  "A UriResolverActor" should "handle a failure response from the request actor" in {
    val reqActor = system.actorOf(Props[StubRequestActor])
    reqActor ! StubRequest(serverPath("/"), null, error = true)
    val resolver = createResolverActor(reqActor)

    resolver ! createRequest("/sub/subFile.txt")
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause.getMessage should be("FAILURE")
  }

  it should "handle a part that cannot be resolved" in {
    val reqActor = system.actorOf(Props[StubRequestActor])
    reqActor ! StubRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! StubRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"), readResourceFile(FolderContent))
    val resolveRequest = createRequest("/sub/nonExisting.txt")
    val resolver = createResolverActor(reqActor)

    resolver ! resolveRequest
    val response = expectMsgType[akka.actor.Status.Failure]
    response.cause.getMessage should be("Cannot resolve nonExisting.txt in " +
      serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw=="))
  }

  it should "resolve a URI" in {
    val reqActor = system.actorOf(Props[StubRequestActor])
    reqActor ! StubRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! StubRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"), readResourceFile(FolderContent))
    val resolver = createResolverActor(reqActor)
    val resolveRequest = createRequest("/sub/subFile.txt")

    resolver ! resolveRequest
    expectMsg(ResolvedUri(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest.uri))
  }

  it should "cache URIs that have already been resolved" in {
    val reqActor = system.actorOf(Props[StubRequestActor])
    reqActor ! StubRequest(serverPath("/"), readResourceFile(RootContent))
    reqActor ! StubRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"), readResourceFile(FolderContent))
    val resolver = createResolverActor(reqActor)
    val resolveRequest1 = createRequest("/bar.txt")
    resolver ! resolveRequest1
    expectMsg(ResolvedUri(serverPath("/uBQQYWockOWLuCROIHviFhU2XayMtps="), resolveRequest1.uri))

    val resolveRequest2 = createRequest("/sub/subFile.txt")
    resolver ! resolveRequest2
    expectMsg(ResolvedUri(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest2.uri))
  }

  it should "combine requests to the same folder" in {
    val reqActor = system.actorOf(Props[StubRequestActor])
    reqActor ! StubRequest(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/"), readResourceFile(FolderContent))
    val resolver = createResolverActor(reqActor)
    val resolveRequest1 = createRequest("/bar.txt")
    val resolveRequest2 = createRequest("/sub/subFile.txt")
    val expResult1 = ResolvedUri(serverPath("/uBQQYWockOWLuCROIHviFhU2XayMtps="), resolveRequest1.uri)
    val expResult2 = ResolvedUri(serverPath("/Q8Xcluxx2ADWaUAtUHLurqSmvw==/Oe3_2W9y1fFSrTj15xaGdt9_rovvGSLPY7NN"),
      resolveRequest2.uri)

    resolver ! resolveRequest1
    resolver ! resolveRequest2
    reqActor ! StubRequest(serverPath("/"), readResourceFile(RootContent))
    val results = List(expectMsgType[ResolvedUri], expectMsgType[ResolvedUri])
    results should contain only(expResult1, expResult2)
  }

  it should "handle a request with an invalid base path" in {
    val reqActor = system.actorOf(Props[StubRequestActor])
    val resolver = createResolverActor(reqActor)
    val path = "/invalid/path/foo.txt"

    resolver ! ResolveUri(path)
    val errResponse = expectMsgType[akka.actor.Status.Failure]
    errResponse.cause.getMessage should include(path)
  }
}

/**
  * A message class to add a stub request to the stub request actor.
  *
  * @param path     the path to be stubbed
  * @param response the response string to be returned for this path
  * @param error    flag whether an error response should be generated
  */
case class StubRequest(path: String, response: String, error: Boolean = false)

/**
  * A data class for storing information about a request that cannot be
  * processed directly because no stub is available.
  *
  * @param request the actual request
  * @param client  the sender of this request
  */
case class PendingRequest(request: SendRequest, client: ActorRef)

/**
  * An actor simulating the HTTP request actor.
  *
  * This actor processes ''SendRequest'' messages. For each request it checks
  * whether it had received a [[StubRequest]] message previously that defines
  * how to handle this request. If so, a response with the corresponding result
  * string is returned. (Otherwise, the request times out.) A once processed
  * request is removed from the stubbing data; so if it is expected again, it
  * has to be stubbed anew.
  *
  * If a request arrives for which no stub is defined yet, it is recorded. On
  * receiving a stub message later on, it is answered accordingly. That way the
  * timing of requests can be controlled in a better way.
  */
class StubRequestActor extends Actor {
  /** The accept header that must be present for all requests. */
  private val ExpAccept = Accept(MediaRange(MediaType.text("xml")))

  /** The map with requests this actor can handle. */
  private var expectedRequests = Map.empty[String, StubRequest]

  /** A map to store requests for which no stub information is available yet. */
  private var pendingRequests = Map.empty[String, PendingRequest]

  override def receive: Receive = {
    case stub@StubRequest(path, _, _) =>
      println("Received StubRequest for " + path)
      pendingRequests.get(path) match {
        case Some(req) => sendResponse(stub, req)
        case None => expectedRequests = expectedRequests + (path -> stub)
      }

    case req@SendRequest(request, _) if checkRequest(request) =>
      val path = request.uri.path.toString()
      println(s"Received request for $path, stubs are ${expectedRequests.keys}.")
      val pendingRequest = PendingRequest(req, sender())
      expectedRequests.get(path) match {
        case Some(stub) =>
          sendResponse(stub, pendingRequest)
          expectedRequests -= path
        case None => pendingRequests += path -> pendingRequest
      }
  }

  /**
    * Sends a response for a pending request.
    *
    * @param stubRequest    the stub that defines the response
    * @param pendingRequest data about the request to be answered
    */
  private def sendResponse(stubRequest: StubRequest, pendingRequest: PendingRequest): Unit = {
    val message = if (stubRequest.error) akka.actor.Status.Failure(new Exception("FAILURE"))
    else {
      val response = HttpResponse(entity = HttpEntity(stubRequest.response))
      ResponseData(response, pendingRequest.request.data)
    }
    pendingRequest.client ! message
  }

  /**
    * Checks whether the given request meets all criteria to query a DAV
    * folder on the server.
    *
    * @param request the request
    * @return a flag whether this request is accepted
    */
  private def checkRequest(request: HttpRequest): Boolean = {
    println("Checking request " + request)
    checkHeader(request, ExpAccept) && checkDepthHeader(request) &&
      request.method.value == "PROPFIND"
  }

  /**
    * Checks whether a specific header is contained in the given request.
    *
    * @param request the request
    * @param header  the expected header
    * @return a flag whether this header was found
    */
  private def checkHeader(request: HttpRequest, header: HttpHeader): Boolean =
    request.headers.contains(header)

  private def checkDepthHeader(request: HttpRequest): Boolean =
    request.headers.exists(h => h.name() == "Depth" && h.value() == "1")
}
