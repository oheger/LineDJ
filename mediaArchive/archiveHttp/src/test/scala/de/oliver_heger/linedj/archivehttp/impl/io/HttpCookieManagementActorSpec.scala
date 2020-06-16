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

package de.oliver_heger.linedj.archivehttp.impl.io

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

object HttpCookieManagementActorSpec {
  /** A test cookie. */
  private val Cookie1 = HttpCookie(name = "cuckoo", value = "beep")

  /** Another test cookie. */
  private val Cookie2 = HttpCookie(name = "foo", value = "bar", path = Some("/"))

  /** Test data for requests. */
  private val Data = new Object

  /** Path for a test request. */
  private val RequestPath = "/foo"

  /** Timeout for HTTP requests. */
  private val RequestTimeout = 3.seconds

  /** A list with default headers of the test request. */
  private val DefaultHeaders = List(ETag("some_tag"))

  /** A test request. */
  private val TestRequest = HttpRequest(uri = RequestPath, headers = DefaultHeaders)

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
  * Test class for ''HttpCookieManagementActor''.
  */
class HttpCookieManagementActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers {
  def this() = this(ActorSystem("HttpCookieManagementActorSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  import HttpCookieManagementActorSpec._

  "HttpCookeManagementActor" should "retry an unauthorized request if there is a cookie from the server" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val response2 = HttpResponse()
    val helper = new CookieActorTestHelper

    helper.expectRequest(TestRequest, response1)
      .expectRequest(withCookie(TestRequest), response2)
      .sendRequest() should be(response2)
  }

  it should "only retry an unauthorized request once" in {
    val response = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val helper = new CookieActorTestHelper

    val exception = helper.expectRequest(TestRequest, response)
      .expectRequest(withCookie(TestRequest), response)
      .sendRequestAndExpectFailure[FailedRequestException]
    exception.response should be(Some(response))
    exception.request.request should be(withCookie(TestRequest))
    exception.request.data should be(Data)
  }

  it should "only retry unauthorized requests" in {
    val response = HttpResponse(status = StatusCodes.Forbidden, headers = setCookieHeaders())
    val helper = new CookieActorTestHelper

    val exception = helper.expectRequest(TestRequest, response)
      .sendRequestAndExpectFailure[FailedRequestException]
    exception.response should be(Some(response))
  }

  it should "store a cookie used for authorization" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized, headers = setCookieHeaders())
    val response2 = HttpResponse()
    val response3 = HttpResponse(status = StatusCodes.Accepted)
    val otherReq = HttpRequest(method = HttpMethods.POST, uri = "/someEndpoint")
    val helper = new CookieActorTestHelper
    helper.expectRequest(TestRequest, response1)
      .expectRequest(withCookie(TestRequest), response2)
      .expectRequest(withCookie(otherReq), response3)
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
    val helper = new CookieActorTestHelper

    helper.expectRequest(TestRequest, response1)
      .expectRequest(withCookie(TestRequest), response2)
      .expectRequest(withCookie(otherReq), response3)
      .expectRequest(withHeader(otherReq, newCookie), response2)
      .sendRequest()
    helper.sendRequest(otherReq) should be(response2)
  }

  /**
    * A test helper class that manages an actor instance to be tested and its
    * dependencies.
    */
  private class CookieActorTestHelper {
    /** The actor simulating the request actor. */
    private val requestActor = createRequestActor()

    /** The cookie management actor to be tested. */
    private val cookieActor = createCookieActor()

    /**
      * Adds a request/response pair to the queue of expected requests. When a
      * request is received it is checked against the next request in the
      * queue. If successful, the given response is returned.
      *
      * @param request  the expected request
      * @param response the response to be returned
      * @return this test helper
      */
    def expectRequest(request: HttpRequest, response: HttpResponse): CookieActorTestHelper = {
      if (response.status.isSuccess()) {
        RequestActorTestImpl.expectRequest(requestActor, request, response)
      } else {
        RequestActorTestImpl.expectFailedRequest(requestActor, request,
          FailedRequestException("Failure", cause = null, response = Some(response),
            request = HttpRequests.SendRequest(request, Data)))
      }
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
      implicit val timeout: Timeout = RequestTimeout
      val testReq = HttpRequests.SendRequest(request, Data)
      val responseData = Await.result(HttpRequests.sendRequest(cookieActor, testReq), RequestTimeout)
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
      * Creates the actor simulating the underlying request actor.
      *
      * @return the simulated request actor
      */
    private def createRequestActor(): ActorRef = system.actorOf(RequestActorTestImpl())

    /**
      * Creates the cookie management actor to be tested.
      *
      * @return the test actor instance
      */
    private def createCookieActor(): ActorRef =
      system.actorOf(HttpCookieManagementActor(requestActor))
  }

}
