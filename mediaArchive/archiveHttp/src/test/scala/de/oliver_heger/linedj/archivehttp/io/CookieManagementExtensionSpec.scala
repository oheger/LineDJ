/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.io

import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.HttpRequestSender.DiscardEntityMode.DiscardEntityMode
import com.github.cloudfiles.core.http.HttpRequestSender.{DiscardEntityMode, FailedResponseException}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, BehaviorTestKit}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, HttpCookie, HttpCookiePair, `Set-Cookie`}
import org.mockito.Mockito.{never, verify}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

object CookieManagementExtensionSpec:
  /** A test HTTP request sent to the test actor. */
  private val TestRequest = HttpRequest(uri = "https://test.example.org/foo")

  /** A data object associated with the test request. */
  private val RequestData = "someRequestData"

  /** A test cookie. */
  private val Cookie1 = HttpCookie(name = "cuckoo", value = "beep")

  /** Another test cookie. */
  private val Cookie2 = HttpCookie(name = "foo", value = "bar", path = Some("/"))

  /**
    * Creates a failed result object with a response that failed with the status
    * code provided.
    *
    * @param request the request to be answered
    * @param status  the status code
    * @param headers headers of the response
    * @param entity  the response entity
    * @return the resulting ''FailedResult'' object
    */
  private def failedResult(request: HttpRequestSender.SendRequest, status: StatusCode,
                           headers: List[HttpHeader] = Nil, entity: ResponseEntity = HttpEntity.Empty):
  HttpRequestSender.FailedResult =
    HttpRequestSender.FailedResult(request,
      FailedResponseException(HttpResponse(status = status, headers = headers, entity = entity)))

  /**
    * Adds the expected cookie header to the given request.
    *
    * @param request the request
    * @return the request with a cookie header
    */
  private def withCookie(request: HttpRequest): HttpRequest =
    val cookieHeader = Cookie(List(HttpCookiePair(Cookie2.name, Cookie2.value),
      HttpCookiePair(Cookie1.name, Cookie1.value)))
    withHeader(request, cookieHeader)

  /**
    * Adds the given header to the headers of the passed in request.
    *
    * @param request the request
    * @param header  the header to be added
    * @return the updated request
    */
  private def withHeader(request: HttpRequest, header: HttpHeader): HttpRequest =
    request.withHeaders(header :: request.headers.toList)

  /**
    * Returns a list with headers to set the test cookies.
    *
    * @return the list with set-cookie headers
    */
  private def setCookieHeaders(): List[HttpHeader] =
    List(Cookie1, Cookie2).map(`Set-Cookie`(_))

/**
  * Test class for ''CookieManagementExtension''.
  */
class CookieManagementExtensionSpec extends AnyFlatSpec with BeforeAndAfterAll with Matchers with MockitoSugar:
  private val testKit = ActorTestKit()
  import CookieManagementExtensionSpec.*
  import testKit.given

  /**
    * Checks the data that was passed when forwarding a request.
    *
    * @param request the request that was forwarded
    */
  private def checkForwardedRequestData(request: HttpRequestSender.SendRequest): Unit =
    request.data match
      case HttpRequestSender.SendRequest(req, data, _, _) =>
        req should be(TestRequest)
        data should be(RequestData)
      case d => fail("Unexpected request data: " + d)

  "CookieManagementExtension" should "handle a successful request" in:
    val response = HttpResponse(status = StatusCodes.Accepted)
    val helper = new CookieExtensionTestHelper

    val finalResult = helper.sendRequest()
      .expectForwardedRequestAndAnswer { request =>
        request.request should be(TestRequest)
        request.discardEntityMode should be(DiscardEntityMode.OnFailure)
        checkForwardedRequestData(request)
        HttpRequestSender.SuccessResult(request, response)
      }.expectSuccessResponse()
    finalResult.request.request should be(TestRequest)
    finalResult.request.data should be(RequestData)
    finalResult.response should be(response)

  it should "propagate the discard mode when forwarding requests" in:
    val helper = new CookieExtensionTestHelper

    val fwdRequest = helper.sendRequest(discardMode = DiscardEntityMode.Never)
      .expectForwardedRequest()
    fwdRequest.discardEntityMode should be(DiscardEntityMode.Never)

  it should "retry an unauthorized request if there is a cookie from the server" in:
    val responseEntity = mock[ResponseEntity]
    val helper = new CookieExtensionTestHelper

    val reqWithCookies = helper.sendRequest()
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders(), entity = responseEntity)
      }.expectForwardedRequest()
    reqWithCookies.request should be(withCookie(TestRequest))
    reqWithCookies.discardEntityMode should be(DiscardEntityMode.OnFailure)
    checkForwardedRequestData(reqWithCookies)
    verify(responseEntity, never()).discardBytes()

  it should "propagate the discard mode when retrying a request" in:
    val helper = new CookieExtensionTestHelper

    val fwdRequest = helper.sendRequest(discardMode = DiscardEntityMode.Never)
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders())
      }.expectForwardedRequest()
    fwdRequest.discardEntityMode should be(DiscardEntityMode.Never)

  it should "discard entity bytes when retrying a failed request if necessary" in:
    val responseEntity = mock[ResponseEntity]
    val helper = new CookieExtensionTestHelper

    helper.sendRequest(discardMode = DiscardEntityMode.Never)
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders(), entity = responseEntity)
      }.expectForwardedRequest()
    verify(responseEntity).discardBytes()

  it should "only retry an unauthorized request once" in:
    val helper = new CookieExtensionTestHelper

    val finalResult = helper.sendRequest()
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders())
      }.expectForwardedRequestAndAnswer { fwdRequest =>
      failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders())
    }.expectFailureResponseWithStatus(StatusCodes.Unauthorized)
    finalResult.cause.asInstanceOf[FailedResponseException].response.status should be(StatusCodes.Unauthorized)

  it should "retry only unauthorized requests" in:
    val helper = new CookieExtensionTestHelper

    val finalResult = helper.sendRequest()
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Forbidden)
      }.expectFailureResponseWithStatus(StatusCodes.Forbidden)
    finalResult.request.request should be(TestRequest)

  it should "store a cookie used for authorization" in:
    val otherRequest = HttpRequest(method = HttpMethods.POST, uri = "/someEndpoint")
    val helper = new CookieExtensionTestHelper

    helper.sendRequest()
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders())
      }.expectForwardedRequestAndAnswer { fwdRequest2 =>
      HttpRequestSender.SuccessResult(fwdRequest2, HttpResponse())
    }.expectSuccessResponse()

    val fwdRequest3 = helper.sendRequest(otherRequest)
      .expectForwardedRequest()
    fwdRequest3.request should be(withCookie(otherRequest))

  it should "update the authorization cookie when it becomes invalid" in:
    val pair = HttpCookiePair("update", "recent")
    val newCookie = Cookie(pair)
    val otherReq = HttpRequest(method = HttpMethods.POST, uri = "/someEndpoint")
    val helper = new CookieExtensionTestHelper

    val fwdRequest = helper.sendRequest()
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized, headers = setCookieHeaders())
      }.expectForwardedRequestAndAnswer { fwdRequest =>
      HttpRequestSender.SuccessResult(fwdRequest, HttpResponse())
    }.sendRequest(otherReq)
      .expectForwardedRequestAndAnswer { fwdRequest =>
        failedResult(fwdRequest, StatusCodes.Unauthorized,
          headers = List(`Set-Cookie`(HttpCookie(pair.name, pair.value))))
      }.expectForwardedRequest()
    fwdRequest.request should be(withHeader(otherReq, newCookie))

  it should "react on a Stop message" in:
    val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()
    val btk = BehaviorTestKit(CookieManagementExtension(probeRequest.ref))

    btk.run(HttpRequestSender.Stop)
    btk.returnedBehavior should be(Behaviors.stopped)
    probeRequest.expectMessage(HttpRequestSender.Stop)

  /**
    * A test helper class managing a test actor instance and its dependencies.
    */
  private class CookieExtensionTestHelper:
    /** A test probe simulating the underlying request actor. */
    private val probeRequest = testKit.createTestProbe[HttpRequestSender.HttpCommand]()

    /** A test probe acting as the client of the extension actor. */
    private val probeReply = testKit.createTestProbe[HttpRequestSender.Result]()

    /** The cookie extension to test. */
    private val cookieExtension = testKit.spawn(CookieManagementExtension(probeRequest.ref))

    /**
      * Sends a request to the test extension actor.
      *
      * @param request     the request to sent
      * @param discardMode the discard entity mode
      * @return this test helper
      */
    def sendRequest(request: HttpRequest = TestRequest, discardMode: DiscardEntityMode = DiscardEntityMode.OnFailure):
    CookieExtensionTestHelper =
      val sendReq = HttpRequestSender.SendRequest(request, RequestData, probeReply.ref, discardMode)
      cookieExtension ! sendReq
      this

    /**
      * Expects a success response to be returned by the test actor and returns
      * it.
      *
      * @return the success response
      */
    def expectSuccessResponse(): HttpRequestSender.SuccessResult =
      probeReply.expectMessageType[HttpRequestSender.SuccessResult]

    /**
      * Expects a failure response to be returned by the test actor and returns
      * it.
      *
      * @return the failure response
      */
    def expectFailureResponse(): HttpRequestSender.FailedResult =
      probeReply.expectMessageType[HttpRequestSender.FailedResult]

    /**
      * Expects a failure response indicating a failed request with the given
      * status code.
      *
      * @param status the expected status code
      * @return the failure response
      */
    def expectFailureResponseWithStatus(status: StatusCode): HttpRequestSender.FailedResult =
      val failureResponse = expectFailureResponse()
      failureResponse.cause match
        case e: FailedResponseException =>
          e.response.status should be(status)
        case r => fail("Unexpected failure: " + r)
      failureResponse

    /**
      * Expects that a request has been forwarded to the underlying request
      * sender actor and returns it.
      *
      * @return the request that has been forwarded
      */
    def expectForwardedRequest(): HttpRequestSender.SendRequest =
      probeRequest.expectMessageType[HttpRequestSender.SendRequest]

    /**
      * Expects that a request has been forwarded to the underlying request
      * sender actor and sends a response back.
      *
      * @param fResponse a function to generate the response to be sent
      * @return this test helper
      */
    def expectForwardedRequestAndAnswer(fResponse: HttpRequestSender.SendRequest => HttpRequestSender.Result):
    CookieExtensionTestHelper =
      val request = expectForwardedRequest()
      request.replyTo ! fResponse(request)
      this

