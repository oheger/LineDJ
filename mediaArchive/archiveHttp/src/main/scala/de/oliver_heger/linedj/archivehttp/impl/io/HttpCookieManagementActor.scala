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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.model.headers.{Cookie, HttpCookiePair, `Set-Cookie`}
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, StatusCodes}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.SendRequest

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HttpCookieManagementActor {
  /** The name of the header to set cookies. */
  private val SetCookieHeader = "set-cookie"

  /**
    * Returns a ''Props'' object for creating a new instance of this actor
    * class.
    *
    * @param httpActor the underlying HTTP request actor
    * @return a ''Props'' object to create a new actor instance
    */
  def apply(httpActor: ActorRef): Props = Props(classOf[HttpCookieManagementActor], httpActor)

  /**
    * An internally data class to store information about requests that may be
    * retried.
    *
    * @param request  the request to be executed
    * @param response the original (failed) response
    * @param client   the client that sent this request
    */
  private case class RetryRequest(request: SendRequest, response: HttpResponse, client: ActorRef)

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

  /**
    * Returns a sequence with all the cookie information defined by the given
    * response.
    *
    * @param response the response
    * @return a list of all cookies
    */
  private def extractCookies(response: HttpResponse): List[HttpCookiePair] =
    response.headers.foldLeft(List.empty[HttpCookiePair]) { (lst, h) =>
      h match {
        case `Set-Cookie`(cookie) => HttpCookiePair(cookie.name, cookie.value) :: lst
        case _ => lst
      }
    }

  /**
    * Returns a sequence with the headers of the given request that are not
    * managed by this actor class.
    *
    * @param request the request
    * @return the filtered headers of this request
    */
  private def filterHeaders(request: HttpRequest): Seq[HttpHeader] =
    request.headers.filter {
      case Cookie(_) => false
      case _ => true
    }

  /**
    * Adds the given cookie header to the request specified. The headers
    * managed by this actor class are also normalized.
    *
    * @param request the request to be manipulated
    * @param cookie  the cookie header to be added
    * @return the updated request
    */
  private def addCookieHeader(request: SendRequest, cookie: Cookie): SendRequest = {
    val nextHeaders = cookie :: filterHeaders(request.request).toList
    val updatedHttpRequest = request.request.copy(headers = nextHeaders)
    request.copy(request = updatedHttpRequest)
  }
}

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
  *
  * @param httpActor the underlying HTTP request actor
  */
class HttpCookieManagementActor(override val httpActor: ActorRef) extends Actor with ActorLogging
  with HttpExtensionActor {

  import HttpCookieManagementActor._
  import context.dispatcher

  /** Stores the cookies to be added to requests. */
  private var cookieHeader: Option[Cookie] = None

  override def receive: Receive = {
    case req: SendRequest =>
      val updatedRequest = cookieHeader.map(c => addCookieHeader(req, c)) getOrElse req
      handleRequest(updatedRequest, sender())

    case rd: RetryRequest =>
      retryRequest(rd)
  }

  /**
    * Handles the execution of a request by forwarding it to the underlying
    * request actor and evaluating the response.
    *
    * @param request the request
    * @param client  the sender of this request
    */
  private def handleRequest(request: SendRequest, client: ActorRef): Unit = {
    // large timeout, as timeouts are handled by the caller
    implicit val timeout: Timeout = Timeout(1.day)
    HttpRequests.sendRequest(httpActor, request) onComplete {
      case Success(responseData) =>
        client ! responseData
      case Failure(FailedRequestException(_, _, Some(failedResponse), _)) if canRetry(failedResponse) =>
        self ! RetryRequest(request, failedResponse, client)
      case Failure(exception) => sendException(client, exception)
    }
  }

  /**
    * Retries a request if possible. It is checked whether the original failed
    * response contained a different cookie header than the cookies managed
    * currently. If this is the case, the cookies are updated, and the request
    * is sent again.
    *
    * @param retryData the data about the request to be retried
    */
  private def retryRequest(retryData: RetryRequest): Unit = {
    val newCookies = extractCookies(retryData.response)
    println("retryRequest(), cookies are " + newCookies + ", cookie header is " + cookieHeader)
    if (cookieHeader.exists(_.cookies == newCookies))
      sendException(retryData.client, FailedRequestException(response = Some(retryData.response),
        message = "Non success response and no retry possible", request = retryData.request, cause = null))
    else {
      val cookie = Cookie(newCookies)
      log.info("Updated cookies to {}.", newCookies)
      val nextHeaders = cookie :: filterHeaders(retryData.request.request).toList
      val nextRequest = retryData.request.request.copy(headers = nextHeaders)
      handleRequest(retryData.request.copy(request = nextRequest), retryData.client)
      cookieHeader = Some(cookie)
    }
  }

  /**
    * Sends an exception to the given client as a response for a failed
    * request.
    *
    * @param client the sender of the request
    * @param ex     the exception to be sent
    */
  private def sendException(client: ActorRef, ex: Throwable): Unit = {
    client ! akka.actor.Status.Failure(ex)
  }
}
