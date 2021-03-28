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
import java.util.regex.Pattern
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.{FormData, HttpMethods, HttpRequest}
import akka.stream.scaladsl.Sink
import akka.util.{ByteString, Timeout}
import com.github.cloudfiles.core.http.Secret
import com.github.cloudfiles.core.http.auth.OAuthTokenData
import de.oliver_heger.linedj.archivehttp.http.HttpRequests

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

object OAuthTokenRetrieverServiceImpl extends OAuthTokenRetrieverService[OAuthConfig, Secret, OAuthTokenData] {
  /** Parameter for the client ID. */
  private val ParamClientId = "client_id"

  /** Parameter for the redirect URI. */
  private val ParamRedirectUri = "redirect_uri"

  /** Parameter for the client secret. */
  private val ParamClientSecret = "client_secret"

  /** Parameter for the grant type. */
  private val ParamGrantType = "grant_type"

  /** Parameter for the refresh token. */
  private val ParamRefreshToken = "refresh_token"

  /** Constant for the refresh token grant type. */
  private val GrantTypeRefreshToken = "refresh_token"

  /** RegEx to extract the access token. */
  private val regAccessToken = jsonPropRegEx("access_token")

  /** RegEx to extract the refresh token. */
  private val regRefreshToken = jsonPropRegEx("refresh_token")

  /** A timeout for invoking the request actor. */
  private implicit val timeout: Timeout = Timeout(1.minute)

  override def refreshToken(httpActor: ActorRef, config: OAuthConfig, secret: Secret, refreshToken: String)
                           (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthTokenData] = {
    val params = Map(ParamClientId -> config.clientID, ParamRedirectUri -> config.redirectUri,
      ParamClientSecret -> secret.secret, ParamRefreshToken -> refreshToken, ParamGrantType -> GrantTypeRefreshToken)
    sendTokenRequest(httpActor, config, params)
  }

  /**
    * Sends a request to the token endpoint of the referenced IDP with the
    * parameters specified. As response a JSON document with token information
    * is expected. The tokens are parsed and returned.
    *
    * @param httpActor the actor for sending HTTP requests
    * @param config    the OAuth configuration
    * @param params    the parameters for the request
    * @param ec        the execution context
    * @param system    the actor system to materialize streams
    * @return a ''Future'' with the tokens obtained from the IDP
    */
  private def sendTokenRequest(httpActor: ActorRef, config: OAuthConfig, params: Map[String, String])
                              (implicit ec: ExecutionContext, system: ActorSystem): Future[OAuthTokenData] = {
    val request = HttpRequests.SendRequest(request = HttpRequest(uri = config.tokenEndpoint,
      entity = FormData(params).toEntity, method = HttpMethods.POST), null)
    for {result <- HttpRequests.sendRequest(httpActor, request)
         content <- responseBody(result)
         tokenData <- extractTokenData(content)
         } yield tokenData
  }

  /**
    * Extracts the text content from the given result object.
    *
    * @param result the result object
    * @param ec     the execution context
    * @param system the actor system to materialize streams
    * @return the text content of the response
    */
  private def responseBody(result: HttpRequests.ResponseData)
                          (implicit ec: ExecutionContext, system: ActorSystem): Future[String] = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    result.response.entity.dataBytes.runWith(sink).map(_.utf8String)
  }

  /**
    * Tries to extract the token information from the given response from an
    * IDP. If not both an access and a refresh token can be found, the
    * resulting future fails.
    *
    * @param response the IDP response
    * @return a future with the extracted token data
    */
  private def extractTokenData(response: String): Future[OAuthTokenData] = {
    val optTokens = for {access <- regAccessToken.findFirstMatchIn(response)
                         refresh <- regRefreshToken.findFirstMatchIn(response)
                         } yield OAuthTokenData(accessToken = access.group(1), refreshToken = refresh.group(1))
    optTokens.fold[Future[OAuthTokenData]](Future.failed(new IOException(
      s"Could not extract token data from IDP response: '$response'.")))(Future.successful)
  }

  /**
    * Creates a regular expression that matches the value of the given JSON
    * property.
    *
    * @param property the name of the property
    * @return the regular expression for this property
    */
  private def jsonPropRegEx(property: String): Regex =
    raw""""${Pattern.quote(property)}"\s*:\s*"([^"]+)"""".r
}
