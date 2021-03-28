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

import java.io.IOException
import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Status}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.model.{ContentTypes, HttpMethods, HttpRequest, HttpResponse}
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import akka.util.ByteString
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import de.oliver_heger.linedj.AsyncTestHelper
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object OAuthTokenRetrieverServiceImplSpec {
  /** The host name of the test IDP. */
  private val Host = "my-idp.org"

  /** The path of the authorization endpoint. */
  private val AuthPath = "/authorize"

  /** The path of the token endpoint. */
  private val TokenPath = "/tokens"

  /** The client secret used by tests. */
  private val ClientSecret = "test_client_secret"

  /** A test configuration object. */
  private val TestConfig = OAuthConfig(authorizationEndpoint = s"https://$Host$AuthPath",
    tokenEndpoint = s"https://$Host$TokenPath", scope = "foo bar", clientID = "testClient",
    redirectUri = "http://localhost:12345")

  /** Test token data. */
  private val TestTokens = OAuthTokenData("testAccessToken", "testRefreshToken")

  /**
    * A map with the expected parameters for a request to refresh the access
    * token.
    */
  private val RefreshTokenParams = Map("client_id" -> TestConfig.clientID,
    "redirect_uri" -> TestConfig.redirectUri, "client_secret" -> ClientSecret,
    "refresh_token" -> TestTokens.refreshToken, "grant_type" -> "refresh_token")

  /** A valid response of the IDP for a token request. */
  private val TokenResponse =
    s"""
       |{
       |  "token_type": "bearer",
       |  "expires_in": 3600,
       |  "scope": "${TestConfig.scope}",
       |  "access_token": "${TestTokens.accessToken}",
       |  "refresh_token": "${TestTokens.refreshToken}"
       |}
       |""".stripMargin

  /**
    * Validates some basic properties of a request and returns a future with
    * the result.
    *
    * @param req     the request
    * @param expPath the expected path
    * @param ec      the execution context
    * @return the validation result
    */
  private def validateRequestProperties(req: HttpRequest, expPath: String)
                                       (implicit ec: ExecutionContext): Future[Done] = Future {
    if (req.uri.path.toString() != expPath) {
      throw new IllegalArgumentException(s"Wrong path: got ${req.uri.path}, want $expPath")
    }
    if (req.method != HttpMethods.POST) {
      throw new IllegalArgumentException(s"Wrong method; got ${req.method}, want POST")
    }
    if (!req.header[`Content-Type`].map(_.contentType).contains(ContentTypes.`application/x-www-form-urlencoded`)) {
      throw new IllegalArgumentException(s"Wrong content type; got ${req.header[`Content-Type`]}")
    }
    Done
  }

  /**
    * Validates the form parameters in the request body and returns a future
    * with the result.
    *
    * @param req       the request
    * @param expParams the expected parameters
    * @param ec        the execution context
    * @param system    the actor system to materialize streams
    * @return the validation result
    */
  private def validateFormParameters(req: HttpRequest, expParams: Map[String, String])
                                    (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    req.entity.dataBytes.runWith(sink)
      .map(bs => Query(bs.utf8String))
      .map { query =>
        if (query.toMap != expParams) {
          throw new IllegalArgumentException(s"Wrong parameters; got ${query.toMap}, want $expParams")
        }
        Done
      }
  }

  /**
    * Validates an HTTP request against given criteria.
    *
    * @param req       the request
    * @param expPath   the expected path
    * @param expParams the expected parameters
    * @param ec        the execution context
    * @param system    the actor system to materialize streams
    * @return the validation result
    */
  private def validateRequest(req: HttpRequest, expPath: String, expParams: Map[String, String])
                             (implicit ec: ExecutionContext, system: ActorSystem): Future[Done] =
    for {_ <- validateRequestProperties(req, expPath)
         res <- validateFormParameters(req, expParams)
         } yield res

  /**
    * An actor class used to mock an HTTP request actor.
    *
    * The class checks a request against expected data and sends a response.
    *
    * @param expPath   the expected path for the request
    * @param expParams the expected form parameters
    * @param response  the response to be returned
    */
  class HttpStubActor(expPath: String, expParams: Map[String, String], response: Try[String]) extends Actor {
    import context.system

    override def receive: Receive = {
      case req: HttpRequests.SendRequest =>
        implicit val ec: ExecutionContext = context.dispatcher
        val caller = sender()
        (for {_ <- validateRequest(req.request, expPath, expParams)
              respStr <- Future.fromTry(response)
              resp <- createResponse(req, respStr)
              } yield resp) onComplete {
          case Success(result) =>
            caller ! result
          case Failure(exception) =>
            caller ! Status.Failure(exception)
        }
    }

    /**
      * Generates a result for the given request with the content specified.
      *
      * @param req     the request
      * @param content the content of the response
      * @return the result object
      */
    private def createResponse(req: HttpRequests.SendRequest, content: String):
    Future[HttpRequests.ResponseData] = {
      val response = HttpResponse(entity = content)
      Future.successful(HttpRequests.ResponseData(response, req.data))
    }
  }

}

/**
  * Test class for ''OAuthTokenRetrieverServiceImpl''.
  */
class OAuthTokenRetrieverServiceImplSpec(testSystem: ActorSystem) extends TestKit(testSystem) with AnyFlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("OAuthTokenRetrieverServiceSpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import OAuthTokenRetrieverServiceImplSpec._
  import system.dispatcher

  /**
    * Convenience function to create a stub actor with the given parameters.
    *
    * @param expParams the expected parameters in the request
    * @param response  the response to be returned
    * @return the stub HTTP actor
    */
  private def createStubActor(expParams: Map[String, String], response: Try[String]): ActorRef =
    system.actorOf(Props(classOf[HttpStubActor], TokenPath, expParams, response))

  "OAuthTokenRetrieverServiceImpl" should "report an exception when refreshing a token" in {
    val exception = new IllegalStateException("no token")
    val httpActor = createStubActor(RefreshTokenParams, Failure(exception))

    val ex = expectFailedFuture[Throwable](OAuthTokenRetrieverServiceImpl.refreshToken(httpActor,
      TestConfig, Secret(ClientSecret), TestTokens.refreshToken))
    ex should be(exception)
  }

  it should "handle an unexpected response from the IDP" in {
    val respText = "a strange response?!"
    val httpActor = createStubActor(RefreshTokenParams, Success(respText))

    val ex = expectFailedFuture[IOException](OAuthTokenRetrieverServiceImpl.refreshToken(httpActor,
      TestConfig, Secret(ClientSecret), TestTokens.refreshToken))
    ex.getMessage should include(respText)
  }

  it should "execute a successful refresh token request" in {
    val httpActor = createStubActor(RefreshTokenParams, Success(TokenResponse))

    futureResult(OAuthTokenRetrieverServiceImpl.refreshToken(httpActor, TestConfig, Secret(ClientSecret),
      TestTokens.refreshToken)) should be(TestTokens)
  }
}
