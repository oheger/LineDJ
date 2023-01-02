/*
 * Copyright 2015-2023 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package de.oliver_heger.linedj.player.engine.radio.actors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}

private object HttpStreamLoader {
  /** The maximum number of redirects handled by this class. */
  private val MaxRedirects = 20

  /**
    * Determines the URI for a redirect based on the original request URI and
    * the URI from the ''Location'' header. Handles relative and absolute URIs.
    *
    * @param requestUri  the original request URI
    * @param redirectUri the URI found in the ''Location'' header
    * @return the resulting URI to redirect to
    */
  private[actors] def constructRedirectUri(requestUri: Uri, redirectUri: Uri): Uri =
    if (redirectUri.isAbsolute) redirectUri
    else redirectUri.withAuthority(requestUri.authority).withScheme(requestUri.scheme)

  /**
    * Convenience function to construct a failed ''Future'' for a failed
    * request processing.
    *
    * @param message the error message
    * @return the resulting failed ''Future''
    */
  private def failRequest(message: String): Future[HttpResponse] = Future.failed(new IOException(message))
}

/**
  * A helper class for doing HTTP communication for loading radio streams.
  *
  * This class allows sending HTTP requests and implements some more advanced
  * functionality, e.g. for handling errors and redirects.
  */
private class HttpStreamLoader(implicit val actorSystem: ActorSystem) {
  /** The execution context for dealing with futures. */
  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  /** The object for sending HTTP requests. */
  private val http = Http()

  import HttpStreamLoader._

  /**
    * Processes the given request and returns a ''Future'' with the response.
    * The status code of the response is checked; if it is not successful, the
    * resulting ''Future'' fails. Redirects are also handled.
    *
    * @param request the request to send
    * @return a ''Future'' with the response
    */
  def sendRequest(request: HttpRequest): Future[HttpResponse] =
    sendRequestAndHandleRedirects(request)

  /**
    * Processes a request to send with redirect handling. Fails if too many
    * redirects are encountered.
    *
    * @param request       the request to send
    * @param redirectCount the current number of redirects for this request
    * @return a ''Future'' with the response
    */
  private def sendRequestAndHandleRedirects(request: HttpRequest, redirectCount: Int = 0): Future[HttpResponse] =
    if (redirectCount == MaxRedirects) failRequest(s"Reached maximum number ($MaxRedirects) of redirects.")
    else http.singleRequest(request) flatMap { response =>
      if (response.status.isRedirection()) {
        handleRedirect(request, response, redirectCount)
      } else if (response.status.isSuccess()) Future.successful(response)
      else failRequest(s"Request $request failed with ${response.status.intValue()} ${response.status.reason()}.")
    }


  /**
    * Handles a redirect response by sending another request to the URI
    * specified in the ''Location'' header. Handles a missing header and an
    * infinite redirect loop.
    *
    * @param request       the current request
    * @param response      the response
    * @param redirectCount the counter for the redirects
    * @return a ''Future'' with the response
    */
  private def handleRedirect(request: HttpRequest, response: HttpResponse, redirectCount: Int): Future[HttpResponse] =
    response.headers[Location].headOption map { location =>
      val nextRequest = request.withUri(constructRedirectUri(request.uri, location.uri))
      sendRequestAndHandleRedirects(nextRequest, redirectCount + 1)
    } getOrElse failRequest("Invalid redirect without Location header.")
}
