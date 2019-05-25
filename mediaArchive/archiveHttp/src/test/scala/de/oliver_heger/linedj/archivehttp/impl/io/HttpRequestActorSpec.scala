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

package de.oliver_heger.linedj.archivehttp.impl.io

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

import akka.Done
import akka.actor.{ActorSystem, Props, Terminated}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source, SourceQueueWithComplete}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import akka.util.{ByteString, Timeout}
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object HttpRequestActorSpec {
  /** A timeout for request execution. */
  private implicit val RequestTimeout: Timeout = Timeout(3.seconds)

  /** The URI to the test server. */
  private val ServerUri = Uri("https://my.server.biz")

  /** Path for a test request. */
  private val RequestPath = "/foo"

  /** A data object to be passed to a request. */
  private val Data = new Object

  /** The size of the request queue. */
  private val QueueSize = 42

  /** An object with test user credentials. */
  private val Credentials = UserCredentials("scott", "tiger")

  /** A test configuration for an HTTP archive. */
  private val TestConfig = HttpArchiveConfig(archiveURI = ServerUri, credentials = Credentials,
    requestQueueSize = QueueSize, archiveName = "MyTestArchive", processorCount = 1,
    processorTimeout = Timeout(1.minute), propagationBufSize = 128, maxContentSize = 10000,
    downloadBufferSize = 65536, downloadMaxInactivity = 30.seconds, downloadReadChunkSize = 100,
    timeoutReadSize = 1024, downloadConfig = null, metaMappingConfig = null, contentMappingConfig = null)

  /** The expected authorization header. */
  private val AuthHeader = Authorization(BasicHttpCredentials(Credentials.userName, Credentials.password))

  /** A test cookie. */
  private val Cookie1 = HttpCookie(name = "cuckoo", value = "beep")

  /** Another test cookie. */
  private val Cookie2 = HttpCookie(name = "foo", value = "bar", path = Some("/"))

  /** A list with default headers of the test request. */
  private val DefaultHeaders = List(ETag("some_tag"))

  /** A test request. */
  private val TestRequest = HttpRequest(uri = RequestPath, headers = DefaultHeaders)

  /**
    * A case class storing data about an expected request.
    *
    * @param request  the request
    * @param response the tried response to be returned
    */
  private case class ExpectedRequest(request: HttpRequest, response: Try[HttpResponse])

  /**
    * Adds the authorized header to the given request.
    *
    * @param request the request
    * @return the request with the test authorized header
    */
  private def authorized(request: HttpRequest): HttpRequest =
    withHeader(request, AuthHeader)

  /**
    * Adds the expected cookie header to the given request.
    *
    * @param request the request
    * @return the request with a cookie header
    */
  private def withCookie(request: HttpRequest): HttpRequest = {
    val cookieHeader = Cookie(List(HttpCookiePair(Cookie2.name, Cookie2.value),
      HttpCookiePair(Cookie1.name, Cookie1.value)))
    withHeader(request, cookieHeader)
  }

  /**
    * Adds the given header to the headers of the passed in request.
    *
    * @param request the request
    * @param header  the header to be added
    * @return the updated request
    */
  private def withHeader(request: HttpRequest, header: HttpHeader): HttpRequest =
    request.copy(headers = header :: request.headers.toList)

  /**
    * Returns a list with headers to set the test cookies.
    *
    * @return the list with set-cookie headers
    */
  private def setCookieHeaders(): List[HttpHeader] =
    List(Cookie1, Cookie2).map(`Set-Cookie`(_))
}

/**
  * Test class for ''HttpRequestActor''.
  */
class HttpRequestActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("HttpRequestActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  import HttpRequestActorSpec._

  "A HttpRequestActor" should "create correct properties" in {
    val props = HttpRequestActor(TestConfig)

    classOf[HttpRequestActor].isAssignableFrom(props.actorClass()) shouldBe true
    classOf[HttpFlowFactory].isAssignableFrom(props.actorClass()) shouldBe true
    props.args should be(List(TestConfig))
  }

  it should "handle a successful request" in {
    val expResponse = HttpResponse()
    val helper = new RequestActorTestHelper

    val response = helper.expectRequest(authorized(TestRequest), expResponse)
      .sendRequest()
    response should be(expResponse)
  }

  it should "handle a request that causes an exception" in {
    val exception = new IOException("Failed request")
    val helper = new RequestActorTestHelper

    val responseException = helper.expectFailedRequest(authorized(TestRequest), exception)
      .sendRequestAndExpectFailure[FailedRequestException]
    responseException.cause should be(exception)
    responseException.response shouldBe 'empty
    responseException.data should be(Data)
  }

  it should "fail the response future for a non-success response" in {
    val response = HttpResponse(status = StatusCodes.BadRequest)
    val helper = new RequestActorTestHelper

    val exception = helper.expectRequest(authorized(TestRequest), response)
      .sendRequestAndExpectFailure[FailedRequestException]
    exception.response should be(Some(response))
    exception.data should be(Data)
    exception.cause should be(null)
  }

  it should "discard the entity bytes of a non-success response" in {
    val content = ByteString("This is the body of the test response.")
    val entity = mock[ResponseEntity]
    val discardedEntity = new HttpMessage.DiscardedEntity(Future.successful(Done))
    when(entity.dataBytes).thenReturn(Source.single(content))
    when(entity.discardBytes()(any(classOf[Materializer]))).thenReturn(discardedEntity)
    val response = HttpResponse(status = StatusCodes.Unauthorized, entity = entity)
    val helper = new RequestActorTestHelper

    val exception = helper.expectRequest(authorized(TestRequest), response)
      .sendRequestAndExpectFailure[FailedRequestException]
    verify(entity).discardBytes()(any())
    exception.data should be(Data)
  }

  it should "retry an unauthorized request if there is a cookie from the server" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val response2 = HttpResponse()
    val helper = new RequestActorTestHelper

    helper.expectRequest(authorized(TestRequest), response1)
      .expectRequest(authorized(withCookie(TestRequest)), response2)
      .sendRequest() should be(response2)
  }

  it should "only retry an unauthorized request once" in {
    val response = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val helper = new RequestActorTestHelper

    val exception = helper.expectRequest(authorized(TestRequest), response)
      .expectRequest(authorized(withCookie(TestRequest)), response)
      .sendRequestAndExpectFailure[FailedRequestException]
    exception.response should be(Some(response))
    exception.data should be(Data)
  }

  it should "only retry unauthorized requests" in {
    val response = HttpResponse(status = StatusCodes.Forbidden, headers = setCookieHeaders())
    val helper = new RequestActorTestHelper

    val exception = helper.expectRequest(authorized(TestRequest), response)
      .sendRequestAndExpectFailure[FailedRequestException]
    exception.response should be(Some(response))
  }

  it should "store a cookie used for authorization" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val response2 = HttpResponse()
    val response3 = HttpResponse(status = StatusCodes.Accepted)
    val otherReq = HttpRequest(method = HttpMethods.POST, uri = "/someEndpoint")
    val helper = new RequestActorTestHelper
    helper.expectRequest(authorized(TestRequest), response1)
      .expectRequest(authorized(withCookie(TestRequest)), response2)
      .expectRequest(authorized(withCookie(otherReq)), response3)
      .sendRequest()

    helper.sendRequest(otherReq) should be(response3)
  }

  it should "update the authorization cookie when it becomes invalid" in {
    val pair = HttpCookiePair("update", "recent")
    val newCookie = Cookie(pair)
    val response1 = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val response2 = HttpResponse()
    val response3 = HttpResponse(status = StatusCodes.Unauthorized,
      headers = List(`Set-Cookie`(HttpCookie(pair.name, pair.value))))
    val otherReq = HttpRequest(method = HttpMethods.POST, uri = "/someEndpoint")
    val helper = new RequestActorTestHelper

    helper.expectRequest(authorized(TestRequest), response1)
      .expectRequest(authorized(withCookie(TestRequest)), response2)
      .expectRequest(authorized(withCookie(otherReq)), response3)
      .expectRequest(authorized(withHeader(otherReq, newCookie)), response2)
      .sendRequest()
    helper.sendRequest(otherReq) should be(response2)
  }

  it should "close the request queue when it is stopped" in {
    val queue = mock[SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])]]
    val requestActor = system.actorOf(Props(new HttpRequestActor(TestConfig) with HttpFlowFactory with SystemPropertyAccess {
      override def requestQueue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = queue
    }))

    val watcher = TestProbe()
    watcher watch requestActor
    system stop requestActor
    watcher.expectMsgType[Terminated]
    verify(queue).complete()
  }

  /**
    * A test helper class that manages a test actor instance and some helper
    * objects.
    */
  private class RequestActorTestHelper {
    /** A queue that stores expected requests and their responses. */
    private val expectedRequestQueue = new LinkedBlockingQueue[(HttpRequest, Try[HttpResponse])]

    /** Reference to the actor to be tested. */
    private val requestActor = createTestActor()

    /**
      * Adds a request/response pair to the queue of expected requests. When a
      * request is received it is checked against the next request in the
      * queue. If successful, the given response is returned.
      *
      * @param request  the expected request
      * @param response the response to be returned
      * @return this test helper
      */
    def expectRequest(request: HttpRequest, response: HttpResponse): RequestActorTestHelper = {
      expectedRequestQueue offer(request, Success(response))
      this
    }

    /**
      * Adds a request to the queue of expected requests that should trigger an
      * exception. If the given request is received, the exception will be
      * returned.
      *
      * @param request the expected request
      * @param failure the exception to answer this request
      * @return this test helper
      */
    def expectFailedRequest(request: HttpRequest, failure: Throwable): RequestActorTestHelper = {
      expectedRequestQueue offer(request, Failure(failure))
      this
    }

    /**
      * Sends a test request to the test actor, expects a successful
      * response and returns it.
      *
      * @param request the test request to be sent
      * @return the response from the test actor
      */
    def sendRequest(request: HttpRequest = TestRequest): HttpResponse = {
      val futResponse = requestActor ? HttpRequestActor.SendRequest(request, Data)
      val responseData = Await.result(futResponse.mapTo[HttpRequestActor.ResponseData], RequestTimeout.duration)
      responseData.data should be(Data)
      responseData.response
    }

    /**
      * Sends the test request to the test actor and expects an exception to be
      * thrown. The exception is returned.
      *
      * @param t the class tag
      * @tparam E the type of the expected exception
      * @return the exception
      */
    def sendRequestAndExpectFailure[E <: AnyRef](implicit t: ClassTag[E]): E = intercept[E] {
      sendRequest()
    }

    /**
      * Creates a reference to the actor to be tested.
      *
      * @return the test actor
      */
    private def createTestActor(): TestActorRef[HttpRequestActor] =
      TestActorRef(createTestActorProps())

    /**
      * Creates properties to create a test actor instance. The actor uses a
      * mock HTTP flow that returns predefined responses.
      *
      * @return the ''Props'' for a test actor instance
      */
    private def createTestActorProps(): Props =
      Props(new HttpRequestActor(TestConfig) with HttpFlowFactory with SystemPropertyAccess {
        override def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, system: ActorSystem):
        Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
          uri should be(TestConfig.archiveURI)
          Flow[(HttpRequest, T)] map { t =>
            val (req, triedResp) = expectedRequestQueue.poll()
            t._1 should be(req)
            (triedResp, t._2)
          }
        }
      })
  }

}
