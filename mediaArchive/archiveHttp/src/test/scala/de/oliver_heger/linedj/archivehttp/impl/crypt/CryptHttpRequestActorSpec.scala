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
import java.nio.file.Files

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl.ExpectRequest
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.{ResponseData, SendRequest, XRequestPropsHeader}
import de.oliver_heger.linedj.archivehttp.impl.crypt.UriResolverActor.{ResolveUri, ResolvedUri}
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException
import de.oliver_heger.linedj.crypt.AESKeyGenerator
import de.oliver_heger.linedj.{AsyncTestHelper, FileTestHelper}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object CryptHttpRequestActorSpec {
  /** A test URI representing a plain request. */
  private val PlainUri = Uri("/this/is/a/test/uri.mp3")

  /** A simulated encrypted URI as result of a resolve request. */
  private val CryptUri = Uri("/xxx/yy/z/0123/456pq2")

  /** The password for crypt operations. */
  private val Password = "secret_password!"

  /** Key specification for the test password. */
  private val Key = new AESKeyGenerator().generateKey(Password)

  /** Additional data passed with the request. */
  private val RequestData = "some request data"

  /** Timeout for resolve operations. */
  private val ResolveTimeout = Timeout(100.millis)

  /** The test request to be sent to the actor under test. */
  private val TestRequest = HttpRequest(uri = PlainUri)

  /**
    * The message to be sent to the actor under test to execute the test
    * request.
    */
  private val TestRequestMessage = SendRequest(TestRequest, RequestData)
}

/**
  * Test class for ''CryptHttpRequestActor''.
  */
class CryptHttpRequestActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("CryptHttpRequestActorSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import CryptHttpRequestActorSpec._

  "A CryptHttpRequestActor" should "handle a successful request" in {
    val helper = new RequestActorTestHelper

    helper.prepareSuccessfulHttpRequest()
      .prepareSuccessfulResolveOperation()
      .send(TestRequestMessage)
    val responseMsg = expectMsgType[ResponseData]
    responseMsg.data should be(RequestData)
    val response = responseMsg.response
    response.status should be(StatusCodes.OK)

    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val content = futureResult(response.entity.dataBytes.runWith(sink))
    content.utf8String should be(FileTestHelper.TestData)
  }

  it should "handle a successful request with a no-decrypt header" in {
    val entity = HttpEntity(FileTestHelper.TestData)
    val request = HttpRequest(uri = PlainUri, headers =
      List(XRequestPropsHeader.withProperties(HttpRequests.HeaderPropNoDecrypt)))
    val helper = new RequestActorTestHelper

    helper.prepareHttpRequest(Success(HttpResponse(entity = entity)), request.copy(uri = CryptUri))
      .prepareSuccessfulResolveOperation()
      .send(TestRequestMessage.copy(request = request))
    val responseMsg = expectMsgType[ResponseData]
    val response = responseMsg.response

    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    val content = futureResult(response.entity.dataBytes.runWith(sink))
    content.utf8String should be(FileTestHelper.TestData)
  }

  it should "handle a failed resolve operation" in {
    val exception = new IOException("Resolve error")
    val helper = new RequestActorTestHelper

    helper.prepareFailedResolveOperation(exception)
      .send(TestRequestMessage)
      .expectErrorResponse(exception)
  }

  it should "handle a failure from the request actor" in {
    val exception = new RuntimeException("HTTP Crash")
    val helper = new RequestActorTestHelper

    helper.prepareSuccessfulResolveOperation()
      .prepareFailedHttpRequest(exception)
      .send(TestRequestMessage)
      .expectErrorResponse(exception)
  }

  it should "correctly apply the timeout for resolve operations" in {
    val helper = new RequestActorTestHelper

    val exception = helper.send(TestRequestMessage)
      .receiveErrorResponse()
    exception.cause shouldBe a[TimeoutException]
  }

  /**
    * A test helper class managing a test actor and its dependencies.
    */
  private class RequestActorTestHelper {
    /** Stub for the HTTP request actor. */
    private val requestActor = createRequestActor()

    /** Stub for the URI resolver actor. */
    private val resolverActor = createResolverActor()

    /** The actor instance to be tested. */
    private val cryptRequestActor = createCryptRequestActor()

    /**
      * Prepares the stub HTTP request actor to send a successful response for
      * the test request. An encrypted text is returned as content.
      *
      * @return this test helper
      */
    def prepareSuccessfulHttpRequest(): RequestActorTestHelper = {
      val pathCryptData = resolveResourceFile("encrypted.dat")
      val cryptData = Files.readAllBytes(pathCryptData)
      val response = HttpResponse(entity = HttpEntity(cryptData))
      prepareHttpRequest(Success(response))
    }

    /**
      * Prepares the stub HTTP request actor to send a failed response for the
      * test request.
      *
      * @param cause the cause for the failure
      * @return this test helper
      */
    def prepareFailedHttpRequest(cause: Throwable): RequestActorTestHelper =
      prepareHttpRequest(Failure(cause))

    /**
      * Prepares the stub HTTP request actor to answer the resolved test
      * request with the given response.
      *
      * @param response a ''Try'' with the response
      * @param request  the expected request
      * @return this test helper
      */
    def prepareHttpRequest(response: Try[HttpResponse],
                           request: HttpRequest = HttpRequest(uri = CryptUri)): RequestActorTestHelper = {
      requestActor ! ExpectRequest(request, response)
      this
    }

    /**
      * Prepares the stub resolver actor to correctly resolve the test URI.
      *
      * @return this test helper
      */
    def prepareSuccessfulResolveOperation(): RequestActorTestHelper =
      prepareResolveOperation(Success(CryptUri))

    /**
      * Prepares the stub resolver actor to answer the test resolve request
      * with the given failure.
      *
      * @param cause the failure cause
      * @return this test helper
      */
    def prepareFailedResolveOperation(cause: Throwable): RequestActorTestHelper =
      prepareResolveOperation(Failure(cause))

    /**
      * Sends the given message to the actor to be tested.
      *
      * @param msg the message
      * @return this test helper
      */
    def send(msg: Any): RequestActorTestHelper = {
      cryptRequestActor ! msg
      this
    }

    /**
      * Expects to receive an error response caused by a
      * ''FailedRequestException''. This exception is returned.
      *
      * @return the received request exception
      */
    def receiveErrorResponse(): FailedRequestException =
      expectMsgType[Status.Failure].cause match {
        case ex: FailedRequestException if ex.request.data == RequestData => ex
        case m => fail("Unexpected response: " + m)
      }

    /**
      * Expects an error response from the actor under test that has the given
      * exception as cause.
      *
      * @param exception the cause exception
      * @return this tet helper
      */
    def expectErrorResponse(exception: Throwable): RequestActorTestHelper = {
      val requestException = receiveErrorResponse()
      requestException.cause should be(exception)
      this
    }

    /**
      * Prepares the stub resolver actor to answer a request for the test URI
      * with the given response.
      *
      * @param result the result to be returned
      * @return this test helper
      */
    private def prepareResolveOperation(result: Try[Uri]): RequestActorTestHelper = {
      resolverActor ! PrepareResolveOperation(PlainUri, result)
      this
    }

    /**
      * Creates the test HTTP request actor.
      *
      * @return the test request actor
      */
    private def createRequestActor(): ActorRef =
      system.actorOf(RequestActorTestImpl())

    /**
      * Creates the test resolver actor.
      *
      * @return the test resolver actor
      */
    private def createResolverActor(): ActorRef =
      system.actorOf(Props[ResolverActorTestImpl])

    /**
      * Creates the crypt request actor to be tested.
      *
      * @return the test actor instance
      */
    private def createCryptRequestActor(): ActorRef =
      system.actorOf(Props(classOf[CryptHttpRequestActor], resolverActor, requestActor, Key, ResolveTimeout))
  }

}

/**
  * A message processed by [[ResolverActorTestImpl]] that defines a request to
  * resolve a URI and the response.
  *
  * @param srcUri      the source URI of the request
  * @param resolvedUri the resolve result (can be a failure)
  */
case class PrepareResolveOperation(srcUri: Uri, resolvedUri: Try[Uri])

/**
  * An actor implementation that simulates the URI resolver actor.
  */
class ResolverActorTestImpl extends Actor {
  /** The mapping the resolve implementation is based on. */
  private var uriMapping = Map.empty[Uri, Try[Uri]]

  override def receive: Receive = {
    case PrepareResolveOperation(srcUri, resolvedUri) =>
      uriMapping += srcUri -> resolvedUri

    case ResolveUri(uri) =>
      val response = uriMapping(uri) match {
        case Success(resolvedUri) =>
          ResolvedUri(resolvedUri, uri)
        case Failure(exception) =>
          akka.actor.Status.Failure(exception)
      }
      sender() ! response
  }
}
