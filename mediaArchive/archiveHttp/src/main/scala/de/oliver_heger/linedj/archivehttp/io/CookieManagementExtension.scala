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
import com.github.cloudfiles.core.http.HttpRequestSender.{FailedResponseException, FailedResult, ForwardedResult}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, HttpCookiePair, `Set-Cookie`}
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, StatusCodes}

import scala.collection.immutable.Seq

/**
  * An HTTP extension actor implementation that adds a special cookie handling
  * to support certain proxies.
  *
  * This actor has the goal to make HTTP requests sent by the framework
  * compatible with certain proxy servers. In specific environments the
  * behavior was observed that a proxy server rejected a valid request with the
  * status code ''UNAUTHORIZED'', but added a special ''Set-Cookie'' header to
  * the response. The request was only successful if the cookie was passed in
  * the header. This kind of cookie management is implemented here.
  */
object CookieManagementExtension:
  /** The name of the header to set cookies. */
  private val SetCookieHeader = "set-cookie"

  /**
    * Returns a behavior that adds the described cookie management to the
    * request sender actor provided.
    *
    * @param requestSender the request sender actor to decorate
    * @return the behavior of the cookie management extension
    */
  def apply(requestSender: ActorRef[HttpRequestSender.HttpCommand]): Behavior[HttpRequestSender.HttpCommand] =
    handleRequests(requestSender, None)

  private def handleRequests(requestSender: ActorRef[HttpRequestSender.HttpCommand], cookieHeader: Option[Cookie]):
  Behavior[HttpRequestSender.HttpCommand] =
    Behaviors.receivePartial:
      case (context, req: HttpRequestSender.SendRequest) =>
        val updatedRequest = cookieHeader.map(c => addCookieHeader(req, c)) getOrElse req.request
        HttpRequestSender.forwardRequest(context, requestSender, updatedRequest, req, req.discardEntityMode)
        Behaviors.same

      case (context, ForwardedResult(result@FailedResult(HttpRequestSender.SendRequest(_,
      data: HttpRequestSender.SendRequest, _, _), cause: FailedResponseException)))
        if canRetry(cause.response) =>
        val newCookies = extractCookies(cause.response)
        if cookieHeader.exists(_.cookies == newCookies) then
          sendResult(result)
        else

          implicit val mat: ActorSystem[Nothing] = context.system
          result.ensureResponseEntityDiscarded()
          val cookie = Cookie(newCookies)
          context.log.info("Updated cookies to {}.", newCookies)
          val nextRequest = addCookieHeader(data, cookie)
          HttpRequestSender.forwardRequest(context, requestSender, nextRequest, data, data.discardEntityMode)
          handleRequests(requestSender, Some(cookie))

      case (_, ForwardedResult(result)) =>
        sendResult(result)

      case (_, HttpRequestSender.Stop) =>
        requestSender ! HttpRequestSender.Stop
        Behaviors.stopped

  private def sendResult(srcResult: HttpRequestSender.Result): Behavior[HttpRequestSender.HttpCommand] =
    val result = HttpRequestSender.resultFromForwardedRequest(srcResult)
    result.request.replyTo ! result
    Behaviors.same

  /**
    * Returns a sequence with all the cookie information defined by the given
    * response.
    *
    * @param response the response
    * @return a list of all cookies
    */
  private def extractCookies(response: HttpResponse): List[HttpCookiePair] =
    response.headers.foldLeft(List.empty[HttpCookiePair]) { (lst, h) =>
      h match
        case `Set-Cookie`(cookie) => HttpCookiePair(cookie.name, cookie.value) :: lst
        case _ => lst
    }

  /**
    * Returns a sequence with the headers of the given request that are not
    * managed by this actor class.
    *
    * @param request the request
    * @return the filtered headers of this request
    */
  private def filterHeaders(request: HttpRequest): Seq[HttpHeader] =
    request.headers.filter:
      case Cookie(_) => false
      case _ => true

  /**
    * Adds the given cookie header to the request specified. The headers
    * managed by this actor class are also normalized.
    *
    * @param request the request to be manipulated
    * @param cookie  the cookie header to be added
    * @return the updated request
    */
  private def addCookieHeader(request: HttpRequestSender.SendRequest, cookie: Cookie): HttpRequest =
    val nextHeaders = cookie :: filterHeaders(request.request).toList
    request.request.withHeaders(nextHeaders)

  /**
    * Checks whether a failed response can be retried. This is the case for
    * certain status codes and if it contains cookies.
    *
    * @param response the response
    * @return a flag whether this response can be retried
    */
  private def canRetry(response: HttpResponse): Boolean =
    response.status == StatusCodes.Unauthorized && hasCookies(response)

  /**
    * Checks whether the given response contains at least one header to set a
    * cookie.
    *
    * @param response the response
    * @return a flag whether a set-cookie header was found
    */
  private def hasCookies(response: HttpResponse): Boolean =
    response.headers.exists(_.is(SetCookieHeader))
