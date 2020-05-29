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

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.{ResponseData, SendRequest}
import de.oliver_heger.linedj.utils.SystemPropertyAccess

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object HttpRequestActor {

  /**
    * Returns an object with properties to create a new actor instance that is
    * initialized based on the given configuration.
    *
    * @param config the configuration of the HTTP archive
    * @return the properties to create a new actor instance
    */
  def apply(config: HttpArchiveConfig): Props =
    apply(config.archiveURI, config.requestQueueSize)

  /**
    * Returns an object with properties to create a new actor instance that is
    * initialized based on the given parameters.
    *
    * @param host             the URI of the host to send requests to
    * @param requestQueueSize the size of the request queue
    * @return the properties to create a new actor instance
    */
  def apply(host: Uri, requestQueueSize: Int): Props =
    Props(classOf[HttpRequestActorImpl], host, requestQueueSize)

  private class HttpRequestActorImpl(uri: Uri, requestQueueSize: Int) extends HttpRequestActor(uri, requestQueueSize)
    with HttpFlowFactory with SystemPropertyAccess

}

/**
  * An actor that sends HTTP requests to a specific server.
  *
  * This actor can handle ''SendRequest'' messages by sending the requests
  * specified to the associated server. This is done via an HTTP flow that is
  * created when this actor is initialized.
  *
  * The responses returned by this flow are then sent back to the sender. In
  * case they have not been successful, their entity is discarded, and a future
  * that failed with a [[FailedRequestException]] is returned.
  *
  * @param uri              the URI of the host to which requests are sent
  * @param requestQueueSize the size of the request queue
  */
class HttpRequestActor(uri: Uri, requestQueueSize: Int) extends Actor with ActorLogging {
  this: HttpFlowFactory =>

  /** The execution context. */
  implicit private val ec: ExecutionContext = context.system.dispatcher

  /**
    * Returns the actor system in implicit scope. This is needed for stream
    * materialization.
    *
    * @return the implicit actor system
    */
  implicit private def system: ActorSystem = context.system

  /** The flow for executing requests. */
  private var httpFlow: Flow[(HttpRequest, Promise[HttpResponse]),
    (Try[HttpResponse], Promise[HttpResponse]), Any] = _

  /** The queue acting as source for the stream of requests and a kill switch. */
  private var queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])] = _

  override def preStart(): Unit = {
    super.preStart()
    httpFlow = createHttpFlow[Promise[HttpResponse]](uri)
    queue = createRequestQueue()
  }

  override def receive: Receive = {
    case reqMsg: SendRequest =>
      handleRequest(reqMsg, sender())
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
    Source.queue[(HttpRequest, Promise[HttpResponse])](requestQueueSize, OverflowStrategy.dropNew)
      .via(httpFlow)
      .toMat(Sink.foreach({
        case (Success(resp), p) => p.success(resp)
        case (Failure(e), p) => p.failure(e)
      }))(Keep.left)
      .run()

  /**
    * Handles the execution of a request. The request is added to the request
    * queue, and the resulting future is evaluated.
    *
    * @param requestMsg the message with the request
    * @param client     the sender of this request
    */
  private def handleRequest(requestMsg: SendRequest, client: ActorRef): Unit = {
    val request = requestMsg.request
    log.info("{} {}", request.method.value, request.uri)
    enqueueRequest(request, requestMsg.data) onComplete {
      case Success((response, _)) =>
        handleResponse(requestMsg, response, client)
      case Failure(exception) =>
        sendException(client, FailedRequestException(cause = exception, request = requestMsg, response = None,
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
    * successful.
    *
    * @param requestMsg the message with the request
    * @param response   the response
    * @param client     the sender of the request
    */
  private def handleResponse(requestMsg: SendRequest, response: HttpResponse, client: ActorRef): Unit = {
    log.info("{} {} - {} {}", requestMsg.request.method.value, requestMsg.request.uri,
      response.status.intValue(), response.status.reason())
    verifyResponse(response, requestMsg) onComplete {
      case Success(_) => client ! ResponseData(response, requestMsg.data)
      case Failure(exception) => sendException(client, exception)
    }
  }

  /**
    * Verifies whether a response was successful. If this is the case, a future
    * with the response is returned. Otherwise, the future fails with a
    * [[FailedRequestException]] exception, and the response's entity is
    * discarded.
    *
    * @param response   the response
    * @param requestMsg the message with the original request
    * @return a future with the verified response
    */
  private def verifyResponse(response: HttpResponse, requestMsg: SendRequest): Future[HttpResponse] =
    if (response.status.isSuccess()) Future.successful(response)
    else response.discardEntityBytes().future() flatMap (_ =>
      Future.failed(FailedRequestException("Non success response", response = Some(response),
        request = requestMsg, cause = null)))

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
