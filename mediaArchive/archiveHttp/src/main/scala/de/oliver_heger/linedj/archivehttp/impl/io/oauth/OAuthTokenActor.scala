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

package de.oliver_heger.linedj.archivehttp.impl.io.oauth

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Status}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.crypt.Secret
import de.oliver_heger.linedj.archivehttp.impl.io.HttpRequestActor.SendRequest
import de.oliver_heger.linedj.archivehttp.impl.io.oauth.OAuthTokenActor.{DoRefresh, PendingRequestData, RefreshFailure, TokensRefreshed}
import de.oliver_heger.linedj.archivehttp.impl.io.{FailedRequestException, HttpExtensionActor, HttpRequestActor}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object OAuthTokenActor {

  /**
    * An internally used data class that stores information about a request
    * that cannot be processed directly because the access token has to be
    * refreshed first.
    *
    * @param caller  the calling actor
    * @param request the request
    */
  private case class PendingRequestData(caller: ActorRef, request: SendRequest)

  /**
    * An internal message to trigger a token refresh operation.
    *
    * @param pendingRequest information about the current request
    * @param failedRequest  the request that failed
    */
  private case class DoRefresh(pendingRequest: PendingRequestData, failedRequest: SendRequest)

  /**
    * An internal message class used to report a successful token refresh
    * operation. The new ''OAuthTokenData'' object becomes the current one.
    *
    * @param tokenData the refreshed token material
    */
  private case class TokensRefreshed(tokenData: OAuthTokenData)

  /**
    * An internal message class used to report a failure during a token refresh
    * operation. This is fatal and causes the sync process to be stopped.
    *
    * @param exception the exception
    */
  private case class RefreshFailure(exception: Throwable)

  /**
    * Creates a ''Props'' object for creating a new actor instance based on the
    * parameters specified.
    *
    * @param httpActor     the actor to forward HTTP requests to
    * @param idpHttpActor  the actor for HTTP requests to the IDP
    * @param oauthConfig   the OAuth configuration
    * @param clientSecret  the client secret for the IDP
    * @param initTokenData the initial token pair
    * @param tokenService  the token retriever service
    * @return the ''Props'' for creating a new actor instance
    */
  def apply(httpActor: ActorRef, idpHttpActor: ActorRef, oauthConfig: OAuthConfig,
            clientSecret: Secret, initTokenData: OAuthTokenData,
            tokenService: OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData]): Props =
    Props(classOf[OAuthTokenActor], httpActor, idpHttpActor, oauthConfig,
      clientSecret, initTokenData, tokenService)
}

/**
  * An actor class that adds a bearer token obtained from an OAuth identity
  * provider to HTTP requests.
  *
  * The actor is configured with the parameters of an OAuth identity provider.
  * When a request arrives, an authorization header with the current access
  * token is added before the request is forwarded to the actual HTTP request
  * actor. If the response has status code 401 (indicating that the access
  * token is no longer valid), a request to refresh the token is sent to the
  * IDP. The access token is then updated.
  *
  * The communication with the IDP is done via a dedicated request actor that
  * is passed to the constructor. This actor is stopped when this actor stops.
  *
  * @param httpActor     the actor to forward HTTP requests to
  * @param idpHttpActor  the actor for HTTP requests to the IDP
  * @param oauthConfig   the OAuth configuration
  * @param clientSecret  the client secret for the IDP
  * @param initTokenData the initial token pair
  * @param tokenService  the token retriever service
  */
class OAuthTokenActor(override val httpActor: ActorRef,
                      idpHttpActor: ActorRef,
                      oauthConfig: OAuthConfig,
                      clientSecret: Secret,
                      initTokenData: OAuthTokenData,
                      tokenService: OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData])
  extends Actor with ActorLogging with HttpExtensionActor {
  /** Execution context in implicit scope. */
  private implicit val ec: ExecutionContext = context.dispatcher

  /** The object to materialize streams. */
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * A timeout for the ask patter. Note that here a huge value is used;
    * timeouts are actually handled by the caller of this actor.
    */
  private implicit val timeout: Timeout = Timeout(1.day)

  /**
    * The currently valid token data. This data is updated when another access
    * token has to be retrieved.
    */
  private var currentTokenData = initTokenData

  /** Stores pending requests during a refresh operation. */
  private var pendingRequests = List.empty[PendingRequestData]

  /**
    * @inheritdoc This implementation also stops the HTTP actor for
    *             communicating with the IDP.
    */
  override def postStop(): Unit = {
    idpHttpActor ! PoisonPill
    super.postStop()
  }

  override def receive: Receive = tokensAvailable

  /**
    * Returns the ''Receive'' function to be used while a valid access token is
    * available.
    *
    * @return the ''Receive'' function if an access token is available
    */
  private def tokensAvailable: Receive = {
    case req: SendRequest =>
      val caller = sender()
      HttpRequestActor.sendRequest(httpActor, addAuthorization(req)) onComplete {
        case Success(result) =>
          caller ! result

        case Failure(FailedRequestException(_, _, Some(response), failedReq))
          if response.status == StatusCodes.Unauthorized =>
          self ! DoRefresh(PendingRequestData(caller, req), failedReq)

        case Failure(exception) =>
          caller ! Status.Failure(exception)
      }

    case DoRefresh(pendingRequest, failedRequest) =>
      if (usesCurrentToken(failedRequest)) {
        pendingRequests = pendingRequest :: Nil
        refreshTokens()
      } else self.tell(pendingRequest.request, pendingRequest.caller)
  }

  /**
    * A receive function that is active while a refresh operation for the
    * current access token is in progress.
    *
    * @return the receive function during a refresh operation
    */
  private def refreshing: Receive = {
    case req: SendRequest =>
      pendingRequests = PendingRequestData(sender(), req) :: pendingRequests
      log.info("Queuing request until token refresh is complete.")

    case TokensRefreshed(tokenData) =>
      log.info("Got updated token data.")
      currentTokenData = tokenData
      context become tokensAvailable
      pendingRequests foreach { pr =>
        self.tell(pr.request, pr.caller)
      }
      pendingRequests = Nil

    case RefreshFailure(exception) =>
      log.error(exception, "Could not refresh access token.")
      val respUnauthorized = HttpResponse(status = StatusCodes.Unauthorized)
      pendingRequests foreach { pr =>
        val respEx = FailedRequestException(message = "Could not refresh access token",
          response = Some(respUnauthorized), request = pr.request, cause = null)
        pr.caller ! Status.Failure(respEx)
      }

    case DoRefresh(pendingRequest, _) =>
      // a refresh is already in progress
      pendingRequests = pendingRequest :: pendingRequests
  }

  /**
    * Adds an ''Authorization'' header with the currently valid access token to
    * the given request.
    *
    * @param request the request
    * @return the updated request
    */
  private def addAuthorization(request: SendRequest): SendRequest = {
    val auth = Authorization(OAuth2BearerToken(currentTokenData.accessToken))
    val httpReq = request.request.copy(headers = auth :: request.request.headers.toList)
    request.copy(request = httpReq)
  }

  /**
    * Sends a request to the IDP to refresh the access token. When this is
    * successful, the current tokens are replaced, and all pending requests are
    * processed.
    */
  private def refreshTokens(): Unit = {
    context become refreshing
    log.info("Obtaining a new access token.")
    tokenService.refreshToken(idpHttpActor, oauthConfig, clientSecret, currentTokenData.refreshToken)
      .onComplete {
        case Success(tokenData) =>
          self ! TokensRefreshed(tokenData)
        case Failure(refreshEx) =>
          log.error(refreshEx, "Token refresh failed! Aborting sync process.")
          self ! RefreshFailure(refreshEx)
      }
  }

  /**
    * Checks whether the given request has the current access token set in its
    * ''Authorization'' header. If this is the case, and the request failed
    * with status 401, this means that the access token has probably expired
    * and must be refreshed. If, however, the access token has been changed
    * since the request was sent, the new one should be valid. In this case,
    * the request should just be retried with the new token.
    *
    * @param request the request to be checked
    * @return '''true''' if the request uses the current access token;
    *         '''false''' otherwise
    */
  private def usesCurrentToken(request: SendRequest): Boolean =
    request.request.header[Authorization].exists(_.credentials.token() == currentTokenData.accessToken)
}