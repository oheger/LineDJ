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

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.utils.SystemPropertyAccess

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object HttpRequestActor {
  /** The name of the header to set cookies. */
  private val SetCookieHeader = "set-cookie"

  /**
    * A message triggering [[HttpRequestActor]] to send an HTTP request.
    *
    * The actor sends the given request to the target server. It then returns a
    * [[ResponseData]] object to the sender. When called via the ''ask''
    * pattern the future fails if the request was not successful.
    *
    * @param request the request to be executed
    * @param data    additional request data that is included in the response
    */
  case class SendRequest(request: HttpRequest, data: Any)

  /**
    * A data class representing the response of a request.
    *
    * This class is used by [[HttpRequestActor]] to send the result of a
    * request execution back to the sender.
    *
    * @param response the response from the server
    * @param data     additional request data
    */
  case class ResponseData(response: HttpResponse, data: Any)

  /**
    * Returns an object with properties to create a new actor instance.
    *
    * @param config the configuration of the HTTP archive
    * @return the properties to create a new actor instance
    */
  def apply(config: HttpArchiveConfig): Props =
    Props(classOf[HttpRequestActorImpl], config)

  /**
    * Convenience function to send a request to an HTTP request actor and map
    * the response future accordingly.
    *
    * @param httpActor the HTTP request actor
    * @param request   the request to be sent
    * @param timeout   the timeout for the request execution
    * @return a ''Future'' with the response
    */
  def sendRequest(httpActor: ActorRef, request: SendRequest)(implicit timeout: Timeout): Future[ResponseData] =
    (httpActor ? request).mapTo[ResponseData]

  private class HttpRequestActorImpl(config: HttpArchiveConfig) extends HttpRequestActor(config)
    with HttpFlowFactory with SystemPropertyAccess

  /**
    * An internally data class to store information about requests that may be
    * retried.
    *
    * @param request  the request to be executed
    * @param data     additional data about the request
    * @param response the original (failed) response
    * @param client   the client that sent this request
    */
  private case class RetryRequest(request: HttpRequest, data: Any, response: HttpResponse, client: ActorRef)

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
  private def extractCookies(response: HttpResponse): List[HttpCookiePair] = {
    val cookies = response.headers.foldLeft(List.empty[HttpCookie]) { (lst, h) =>
      h match {
        case `Set-Cookie`(cookie) => cookie :: lst
        case _ => lst
      }
    }
    cookies.map(c => HttpCookiePair(c.name, c.value))
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
      case Authorization(_) => false
      case Cookie(_) => false
      case _ => true
    }
}

/**
  * An actor that sends HTTP requests to a specific server and also handles
  * authorization cookies.
  *
  * This actor can handle ''SendRequest'' messages by sending the requests
  * specified to the associated server. This is done via an HTTP flow that is
  * created when this actor is initialized.
  *
  * The responses returned by this flow are then sent back to the sender. In
  * case they have not been successful, their entity is discarded, and a future
  * that failed with a [[FailedRequestException]] is returned.
  *
  * If a request fails with the status UNAUTHORIZED, the actor checks whether
  * the response contains a ''Set-Cookie'' header. If so, the corresponding
  * cookie is added the request, and the request is retried. This is done for
  * all following requests. This behavior seems to be necessary to deal with
  * certain proxies.
  *
  * @param config the configuration of the HTTP archive
  */
class HttpRequestActor(config: HttpArchiveConfig) extends Actor with ActorLogging {
  this: HttpFlowFactory =>

  import HttpRequestActor._

  /** The execution context. */
  implicit private val ec: ExecutionContext = context.system.dispatcher

  /** The object to materialize streams. */
  implicit private val mat: ActorMaterializer = ActorMaterializer()

  /** The authorization header to be added to each request. */
  private val authHeader = Authorization(BasicHttpCredentials(config.credentials.userName,
    config.credentials.password))

  /** The flow for executing requests. */
  private var httpFlow: Flow[(HttpRequest, Promise[HttpResponse]),
    (Try[HttpResponse], Promise[HttpResponse]), Any] = _

  /** Stores the cookies to be added to requests. */
  private var cookieHeader: Option[Cookie] = None

  /** The queue acting as source for the stream of requests and a kill switch. */
  private var queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = _

  override def preStart(): Unit = {
    super.preStart()
    implicit val actorSystem: ActorSystem = context.system
    httpFlow = createHttpFlow[Promise[HttpResponse]](config.archiveURI)
    queue = createRequestQueue()
  }

  override def receive: Receive = {
    case SendRequest(request, data) =>
      val headers = request.headers.toList
      val headersWithCookie = cookieHeader.map(_ :: headers).getOrElse(headers)
      val extendedHeaders = authHeader :: headersWithCookie
      handleRequest(request.copy(headers = extendedHeaders), data, sender())

    case rd: RetryRequest =>
      retryRequest(rd)
  }

  override def postStop(): Unit = {
    requestQueue.complete()
    super.postStop()
  }

  /**
    * Returns the request queue used by this actor. This is exposed only for
    * testing purposes.
    *
    * @return the actor's request queue
    */
  def requestQueue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = queue

  /**
    * Creates the source of the stream for sending requests to the archive.
    *
    * @return the source for sending requests
    */
  private def createRequestQueue(): SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] =
    Source.queue[(HttpRequest, Promise[HttpResponse])](config.requestQueueSize, OverflowStrategy.dropNew)
      .via(httpFlow)
      .toMat(Sink.foreach({
        case (Success(resp), p) => p.success(resp)
        case (Failure(e), p) => p.failure(e)
      }))(Keep.left)
      .run()

  /**
    * Handles the execution of a request. The request is added to the request
    * queue, and the resulting future is evaluated. If possible, the result is
    * sent to the caller directly. Otherwise, a retry may be necessary.
    *
    * @param request the request
    * @param data    the additional request data
    * @param client  the sender of this request
    */
  private def handleRequest(request: HttpRequest, data: Any, client: ActorRef): Unit = {
    log.info("{} {}", request.method.value, request.uri)
    enqueueRequest(request, data) onComplete {
      case Success((response, d)) =>
        handleResponse(request, d, response, client)
      case Failure(exception) =>
        sendException(client, FailedRequestException(cause = exception, data = data, response = None,
          message = "Server request caused an exception"))
    }
  }

  /**
    * Puts a request into the queue and returns a ''Future'' with the response
    * returned from the archive.
    *
    * @param request the request
    * @param data    the additional request data
    * @return a ''Future'' with the response and the data
    */
  private def enqueueRequest(request: HttpRequest, data: Any): Future[(HttpResponse, Any)] = {
    val responsePromise = Promise[HttpResponse]()
    requestQueue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued =>
        responsePromise.future.map((_, data))
      case QueueOfferResult.Dropped =>
        Future.failed(new RuntimeException("Queue overflowed."))
      case QueueOfferResult.Failure(ex) =>
        Future.failed(ex)
      case QueueOfferResult.QueueClosed =>
        Future.failed(new RuntimeException("Queue was closed."))
    }
  }

  /**
    * Handles a response received from the server. Evaluates the response's
    * status code and transforms it to a failure result if it is not
    * successful. If possible, a failed response is retried.
    *
    * @param request  the request
    * @param data     additional request data
    * @param response the response
    * @param client   the sender of the request
    */
  private def handleResponse(request: HttpRequest, data: Any, response: HttpResponse, client: ActorRef): Unit = {
    log.info("{} {} - {} {}", request.method.value, request.uri, response.status.intValue(),
      response.status.reason())
    verifyResponse(response, data) onComplete {
      case Success(_) => client ! ResponseData(response, data)
      case Failure(FailedRequestException(_, _, Some(failedResponse), _)) if canRetry(failedResponse) =>
        self ! RetryRequest(request, data, failedResponse, client)
      case Failure(exception) => sendException(client, exception)
    }
  }

  /**
    * Verifies whether a response was successful. If this is the case, a future
    * with the response is returned. Otherwise, the future fails with a
    * [[FailedRequestException]] exception, and the response's entity is
    * discarded.
    *
    * @param response the response
    * @param data     additional request data
    * @return a future with the verified response
    */
  private def verifyResponse(response: HttpResponse, data: Any): Future[HttpResponse] =
    if (response.status.isSuccess()) Future.successful(response)
    else response.discardEntityBytes().future() flatMap (_ =>
      Future.failed(FailedRequestException("Non success response", response = Some(response),
        data = data, cause = null)))

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
    if (cookieHeader.exists(_.cookies == newCookies))
      sendException(retryData.client, FailedRequestException(response = Some(retryData.response),
        message = "Non success response and no retry possible", data = retryData.data, cause = null))
    else {
      val cookie = Cookie(newCookies)
      log.info("Updated cookies to {}.", newCookies)
      val nextHeaders = authHeader :: cookie :: filterHeaders(retryData.request).toList
      val nextRequest = retryData.request.copy(headers = nextHeaders)
      handleRequest(nextRequest, retryData.data, retryData.client)
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
