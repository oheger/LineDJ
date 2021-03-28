/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl.io.oauth

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.testkit.{ImplicitSender, TestKit}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.impl.crypt.DepthHeader
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException
import org.mockito.Matchers.{any, eq => argEq}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object OAuthTokenActorSpec {
  /** The URI of the test token endpoint. */
  private val TokenUri = "https://test.idp.org/tokens"

  /** OAuth configuration of the test client. */
  private val TestConfig = OAuthConfig(authorizationEndpoint = "https://auth.idp.org/auth",
    scope = "test run", redirectUri = "https://redirect.uri.org/", clientID = "testClient",
    tokenEndpoint = TokenUri)

  /** Secret of the test client. */
  private val ClientSecret = Secret("theSecretOfTheTestClient")

  /** A test request used by the tests. */
  private val TestRequest = HttpRequest(method = HttpMethods.POST, uri = "http://test.org/foo",
    headers = List(DepthHeader("1")))

  /** Test token data. */
  private val TestTokens = OAuthTokenData(accessToken = "<access_token>", refreshToken = "<refresh_token>")

  /** Token data representing refreshed tokens. */
  private val RefreshedTokens = OAuthTokenData(accessToken = "<new_access>", refreshToken = "<new_refresh>")

  /** Test data associated with a request. */
  private val RequestData = new Object

  /** A test request with the default authorization header. */
  private val TestRequestWithAuth = createAuthorizedTestRequest()

  /**
    * A data class describing a response to be sent by the stub actor. The
    * response can be successful or failed. An optional delay can be
    * configured.
    *
    * @param triedResponse the tried response to be sent
    * @param optDelay      an optional delay
    */
  case class StubResponse(triedResponse: Try[HttpResponse], optDelay: Option[FiniteDuration] = None)

  /**
    * A message processed by ''HttpStubActor'' that defines the responses to be
    * sent for incoming requests. Responses can also fail.
    *
    * @param responses the list with tried responses
    */
  case class StubResponses(responses: List[StubResponse])

  /**
    * A message processed by ''HttpStubActor'' to request information about
    * authorization headers that have been received.
    */
  case object GetAuthorizationHeaders

  /**
    * A message sent as response of an [[GetAuthorizationHeaders]] request.
    *
    * @param headers a list with the headers
    */
  case class AuthorizationHeaders(headers: List[Authorization])

  /**
    * Checks whether the given request matches the test request with an
    * ''Authorization'' header.
    *
    * @param req the request to be checked
    * @return a flag whether the request is as expected
    */
  private def checkRequest(req: HttpRequest): Boolean = {
    val optAuth = req.header[Authorization]
    if (optAuth.isDefined) {
      val noAuthHeaders = req.headers filterNot (_ == optAuth.get)
      val baseReq = req.copy(headers = noAuthHeaders)
      TestRequest == baseReq
    } else false
  }

  /**
    * Creates a test request that has the standard authorization header.
    *
    * @return the authorized test request
    */
  private def createAuthorizedTestRequest(): HttpRequest = {
    val headerAuth = Authorization(OAuth2BearerToken(TestTokens.accessToken))
    val newHeaders = headerAuth :: TestRequest.headers.toList
    TestRequest.copy(headers = newHeaders)
  }

  /**
    * Generates the data structures representing the exception for a failed
    * (non-success) HTTP response.
    *
    * @param response the failed response to be wrapped
    * @return the wrapped failed response
    */
  private def failedResponse(response: HttpResponse): Failure[HttpResponse] =
    Failure(FailedRequestException("Failed response", cause = null, response = Some(response),
      request = HttpRequests.SendRequest(TestRequestWithAuth, RequestData)))

  /**
    * An actor implementation that simulates an HTTP actor. This actor expects
    * ''SendRequest'' messages that must correspond to the test request. These
    * requests are answered by configurable responses. From the requests
    * received the ''Authorization'' headers and recorded and can be queried.
    */
  class HttpStubActor extends Actor {
    /** The responses to send for requests. */
    private var responses = List.empty[StubResponse]

    /** Stores the authorization headers that have been received. */
    private var authHeaders = List.empty[Authorization]

    override def receive: Receive = {
      case StubResponses(stubResponses) =>
        responses = stubResponses

      case req: HttpRequests.SendRequest if checkRequest(req.request) =>
        val result = responses.head.triedResponse match {
          case Success(response) =>
            authHeaders = req.request.header[Authorization].get :: authHeaders
            HttpRequests.ResponseData(response, req.data)
          case Failure(exception) =>
            Status.Failure(exception)
        }
        if (responses.head.optDelay.isDefined) {
          implicit val ec: ExecutionContext = context.dispatcher
          context.system.scheduler.scheduleOnce(responses.head.optDelay.get, sender(), result)
        } else {
          sender() ! result
        }
        responses = responses.tail

      case GetAuthorizationHeaders =>
        sender() ! AuthorizationHeaders(authHeaders.reverse)
    }
  }

}

/**
  * Test class for ''OAuthTokenActor''.
  */
class OAuthTokenActorSpec(testSystem: ActorSystem) extends TestKit(testSystem) with ImplicitSender
  with AnyFlatSpecLike with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("OAuthTokenActorSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import OAuthTokenActorSpec._

  "OAuthTokenActor" should "add the Authorization header to requests" in {
    val response = HttpResponse(status = StatusCodes.Accepted)
    val helper = new TokenActorTestHelper

    helper.initResponses(List(StubResponse(Success(response))))
      .sendTestRequest()
    val result = expectMsgType[HttpRequests.ResponseData]
    result.response should be(response)
    helper.checkAuthorization()
  }

  it should "handle a response status exception from the target actor" in {
    val response = HttpResponse(status = StatusCodes.BadRequest)
    val failedResp = failedResponse(response)
    val responses = List(StubResponse(failedResp))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .sendTestRequest()
    expectMsg(Status.Failure(failedResp.exception))
  }

  it should "obtain another access token when receiving an UNAUTHORIZED status" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Created)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(Success(response2)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(Future.successful(RefreshedTokens))
      .sendTestRequest()
    val result = expectMsgType[HttpRequests.ResponseData]
    result.response should be(response2)
    helper.checkAuthorization(RefreshedTokens.accessToken)
  }

  /**
    * Checks that result messages are received from the test actor (in
    * arbitrary order) that refer to the given HTTP responses.
    *
    * @param helper       the test helper object
    * @param expResponses the expected responses
    */
  private def expectResponses(helper: TokenActorTestHelper, expResponses: HttpResponse*): Unit = {
    val results = (1 to expResponses.size) map (_ => expectMsgType[HttpRequests.ResponseData])
    results.map(_.response) should contain only (expResponses: _*)
    helper.checkAuthorization(RefreshedTokens.accessToken)
  }

  it should "hold incoming requests until the access token has been refreshed" in {
    val promise = Promise[OAuthTokenData]()
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Created)
    val response3 = HttpResponse(status = StatusCodes.Accepted)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(Success(response2)), StubResponse(Success(response3)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(promise.future)
      .sendTestRequest()
    expectNoMessage(100.millis)
    helper.sendTestRequest()
    promise.complete(Success(RefreshedTokens))
    expectResponses(helper, response2, response3)
    helper.verifyTokenRefreshed()
  }

  it should "do only a single refresh operation for concurrent unauthorized requests" in {
    val promise = Promise[OAuthTokenData]()
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Unauthorized)
    val response3 = HttpResponse(status = StatusCodes.Created)
    val response4 = HttpResponse(status = StatusCodes.Accepted)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(failedResponse(response2)),
      StubResponse(Success(response3)), StubResponse(Success(response4)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(promise.future)
      .sendTestRequest()
      .sendTestRequest()
    expectNoMessage(100.millis)
    promise.complete(Success(RefreshedTokens))
    expectResponses(helper, response3, response4)
    helper.verifyTokenRefreshed()
  }

  it should "do only a single refresh operation for concurrent unauthorized responses" in {
    val response1 = HttpResponse(status = StatusCodes.Unauthorized)
    val response2 = HttpResponse(status = StatusCodes.Unauthorized)
    val response3 = HttpResponse(status = StatusCodes.Created)
    val response4 = HttpResponse(status = StatusCodes.Accepted)
    val responses = List(StubResponse(failedResponse(response1)),
      StubResponse(failedResponse(response2), optDelay = Some(100.millis)),
      StubResponse(Success(response3)), StubResponse(Success(response4)))
    val helper = new TokenActorTestHelper

    helper.initResponses(responses)
      .expectTokenRequest(Future.successful(RefreshedTokens))
      .sendTestRequest()
      .sendTestRequest()
    expectResponses(helper, response3, response4)
    helper.verifyTokenRefreshed()
  }

  it should "stop the underlying request actor" in {
    val helper = new TokenActorTestHelper

    helper.verifyShutdown()
  }

  /**
    * A test helper class that manages a test instance and all its
    * dependencies.
    */
  private class TokenActorTestHelper {
    /** Mock for the token service. */
    private val tokenService = mock[OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData]]

    /** The target HTTP actor. */
    private val targetHttpActor = system.actorOf(Props[HttpStubActor])

    /**
      * The actor for interacting with the IDP. Note that this actor is not
      * actually invoked; the reference is just passed to the mock token
      * service.
      */
    private val idpHttpActor = system.actorOf(Props[HttpStubActor])

    /** The actor to be tested. */
    private val tokenActor = createTokenActor()

    /**
      * Prepares the stub HTTP actor to send the given responses for requests.
      *
      * @param responses the list with tried responses
      * @return this test helper
      */
    def initResponses(responses: List[StubResponse]): TokenActorTestHelper = {
      targetHttpActor ! StubResponses(responses)
      this
    }

    /**
      * Sends the test request to the test token actor.
      *
      * @return this test helper
      */
    def sendTestRequest(): TokenActorTestHelper = {
      tokenActor ! HttpRequests.SendRequest(TestRequest, RequestData)
      this
    }

    /**
      * Prepares the mock for the token service to expect a refresh token
      * request that is answered with the given result.
      *
      * @param tokenResult the result ''Future'' for the request
      * @return this test helper
      */
    def expectTokenRequest(tokenResult: Future[OAuthTokenData]): TokenActorTestHelper = {
      when(tokenService.refreshToken(argEq(idpHttpActor), argEq(TestConfig), argEq(ClientSecret),
        argEq(TestTokens.refreshToken))(any(), any())).thenReturn(tokenResult)
      this
    }

    /**
      * Verifies that exactly one refresh operation was performed.
      *
      * @return this test helper
      */
    def verifyTokenRefreshed(): TokenActorTestHelper = {
      verify(tokenService).refreshToken(argEq(idpHttpActor), argEq(TestConfig), argEq(ClientSecret),
        argEq(TestTokens.refreshToken))(any(), any())
      this
    }

    /**
      * Checks the ''Authorization'' header in the requests received by the
      * stub actor. They must contain the given access token.
      *
      * @param expAccessToken the expected access token
      * @return this test helper
      */
    def checkAuthorization(expAccessToken: String = TestTokens.accessToken): TokenActorTestHelper = {
      targetHttpActor ! GetAuthorizationHeaders
      val headers = expectMsgType[AuthorizationHeaders]
      headers.headers should not be 'empty
      headers.headers foreach { auth =>
        auth.credentials.token() should be(expAccessToken)
      }
      this
    }

    /**
      * Checks whether the test actor can be stopped correctly and that the
      * associated request actor for the IDP is stopped as well.
      */
    def verifyShutdown(): Unit = {
      system stop tokenActor
      watch(targetHttpActor)
      expectTerminated(targetHttpActor)
      watch(idpHttpActor)
      expectTerminated(idpHttpActor)
    }

    /**
      * Creates the token actor to be tested.
      *
      * @return the test actor instance
      */
    private def createTokenActor(): ActorRef =
      system.actorOf(OAuthTokenActor(targetHttpActor, idpHttpActor, TestConfig,
        ClientSecret, TestTokens, tokenService))
  }

}
