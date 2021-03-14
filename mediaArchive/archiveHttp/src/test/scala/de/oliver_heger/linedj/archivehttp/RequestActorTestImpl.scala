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

package de.oliver_heger.linedj.archivehttp

import java.io.IOException

import akka.actor.{Actor, ActorRef, Props}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.config.HttpArchiveConfig
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.{ResponseData, SendRequest}
import de.oliver_heger.linedj.archivehttp.impl.io.{FailedRequestException, HttpRequestActor}
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object RequestActorTestImpl {
  /** The class of the HTTP request actor. */
  val ClsRequestActor: Class[_] = requestActorClass()

  /**
    * The URI with the host where the test archive is located (used in the
    * configuration for the HTTP archive).
    */
  val TestArchiveHost = "https://some.archive.org"

  /**
    * The base path of the test archive (used in the configuration for the HTTP
    * archive).
    */
  val TestArchiveBasePath = "/data"

  /**
    * Name of the content file for the test archive (used in the configuration
    * for the HTTP archive).
    */
  val TestArchiveContentFile = "archiveContent.json"

  /**
    * A message processed by [[RequestActorTestImpl]] that configures an
    * expected request and its response.
    *
    * @param request  the expected request
    * @param response the response to be returned for this request
    */
  case class ExpectRequest(request: HttpRequest, response: Try[HttpResponse])

  /**
    * A message processed by [[RequestActorTestImpl]] that initializes the
    * whole request-response mapping. This can be used to expect requests in
    * arbitrary order and define the responses for them.
    *
    * @param mapping the map with expected requests and their responses
    */
  case class InitRequestResponseMapping(mapping: Map[HttpRequest, HttpResponse])

  /**
    * A message processed by [[RequestActorTestImpl]] that initializes the
    * whole request-response mapping and also supports error requests. If a
    * response is a failure, a corresponding exception is sent to the caller.
    *
    * @param mapping the map with expected requests and their responses
    */
  case class InitRequestResponseMappingWithFailures(mapping: Map[HttpRequest, Try[HttpResponse]])

  /**
    * A data class for storing information about a request that cannot be
    * processed directly because no expected request is available.
    *
    * @param request the actual request
    * @param client  the sender of this request
    */
  private case class PendingRequest(request: SendRequest, client: ActorRef)

  /**
    * Returns a ''Props'' object to create a new actor instance.
    *
    * @param failOnUnmatchedRequest the flag to fail for unmatched requests
    * @return the ''Props'' to create a new actor instance
    */
  def apply(failOnUnmatchedRequest: Boolean = true): Props =
    Props(classOf[RequestActorTestImpl], failOnUnmatchedRequest)

  /**
    * Configures the given request actor to expect a successful request.
    *
    * @param ref      the reference to the request actor
    * @param request  the expected request
    * @param response the response to be returned
    */
  def expectRequest(ref: ActorRef, request: HttpRequest, response: HttpResponse): Unit = {
    ref ! ExpectRequest(request, Success(response))
  }

  /**
    * Configures the given request actor to expect a failed request.
    *
    * @param ref       the reference to the request actor
    * @param request   the expected request
    * @param exception the failure to be returned
    */
  def expectFailedRequest(ref: ActorRef, request: HttpRequest, exception: Throwable): Unit = {
    ref ! ExpectRequest(request, Failure(exception))
  }

  /**
    * Creates a test config for an HTTP archive with dummy values.
    *
    * @param protocol the HTTP protocol to set for the config
    * @return the test config
    */
  def createTestArchiveConfig(protocol: HttpArchiveProtocol = null): HttpArchiveConfig =
    HttpArchiveConfig(archiveURI = TestArchiveHost + TestArchiveBasePath + "/" + TestArchiveContentFile,
      archiveName = "test", processorCount = 1, processorTimeout = Timeout(1.minute), propagationBufSize = 100,
      maxContentSize = 1024, downloadBufferSize = 1000, downloadMaxInactivity = 10.seconds,
      downloadReadChunkSize = 8192, timeoutReadSize = 111, downloadConfig = null, metaMappingConfig = null,
      contentMappingConfig = null, requestQueueSize = 100, cryptUriCacheSize = 1000,
      needsCookieManagement = false, protocol = protocol, authFunc = null)

  /**
    * Transforms the given mapping with only successful requests to an enhanced
    * mapping that supports failure responses. Of course, all responses are
    * mapped to ''Success'' objects.
    *
    * @param mapping the mapping to be converted
    * @return the enhanced response mapping
    */
  def toMappingWithFailures(mapping: Map[HttpRequest, HttpResponse]): Map[HttpRequest, Try[HttpResponse]] =
    mapping map { e => (e._1, Success(e._2)) }

  /**
    * Returns the class of the request actor.
    *
    * @return the class of the request actor
    */
  private def requestActorClass(): Class[_] = {
    HttpRequestActor(createTestArchiveConfig()).actorClass()
  }
}

/**
  * A test actor implementation that simulates the sending of requests.
  *
  * Expected requests and their responses can be initialized. Then this actor
  * simulates an ''HttpRequestActor'' by comparing expected requests and
  * sending the configured responses.
  *
  * An instance can be configured with both an ordered queue of expected
  * requests and a map with expected requests. When a request arrives it is
  * first checked whether it is contained in the map; if so, the associated
  * response is sent. Otherwise, the queue is consulted. Here the top request
  * must match exactly the received request.
  *
  * If a request arrives that cannot be matched against the map and the queue
  * is empty, depending on the constructor parameter either an error is sent
  * back or the request is stored. In the latter case, it is processed when a
  * new expected request is added to the queue. This is useful to make tests
  * more flexible regarding timing.
  *
  * @param failOnUnmatchedRequest if '''true''', every unmatched request causes
  *                               an error; if '''false''', the request is
  *                               stored an can later be processed
  */
class RequestActorTestImpl(failOnUnmatchedRequest: Boolean) extends Actor with Matchers {

  import RequestActorTestImpl._

  /** A queue with expected requests and their responses. */
  private var expectedRequests = Queue.empty[ExpectRequest]

  /** A map with requests and their responses. */
  private var requestResponseMapping = Map.empty[HttpRequest, Try[HttpResponse]]

  /** A queue storing the pending requests that could not be processed. */
  private var pendingRequests = Queue.empty[PendingRequest]

  override def receive: Receive = {
    case er: ExpectRequest =>
      expectedRequests = expectedRequests.enqueue(er)
      pendingRequests.dequeueOption foreach { d =>
        pendingRequests = d._2
        checkExpectedRequest(d._1.request, d._1.client)
      }

    case InitRequestResponseMapping(mapping) =>
      requestResponseMapping = toMappingWithFailures(mapping)

    case InitRequestResponseMappingWithFailures(mapping) =>
      requestResponseMapping = mapping

    case req@SendRequest(request, _) =>
      requestResponseMapping get request match {
        case Some(response) =>
          sendTriedResponse(response, req, sender())
        case None =>
          if (expectedRequests.isEmpty) {
            if (failOnUnmatchedRequest) {
              sender() ! akka.actor.Status.Failure(new IOException("Failed request to " + request.uri))
            } else {
              pendingRequests = pendingRequests enqueue PendingRequest(req, sender())
            }
          } else {
            checkExpectedRequest(req, sender())
          }
      }
  }

  /**
    * Checks the given request against the first expected request and sends a
    * corresponding response to the client.
    *
    * @param reqMsg the message with the request
    * @param client the caller
    */
  private def checkExpectedRequest(reqMsg: SendRequest, client: ActorRef): Unit = {
    val (reqData, q) = expectedRequests.dequeue
    expectedRequests = q
    reqMsg.request should be(reqData.request)
    sendTriedResponse(reqData.response, reqMsg, client)
  }

  /**
    * Sends a response back to the caller which can be either successful or a
    * failure.
    *
    * @param response the ''Try'' with the response to send
    * @param request  the original request
    * @param client   the receiver of the message to send
    */
  private def sendTriedResponse(response: Try[HttpResponse], request: SendRequest, client: ActorRef): Unit = {
    response match {
      case Success(resp) =>
        client ! ResponseData(resp, request.data)
      case Failure(exception) =>
        val wrappedEx = exception match {
          case reqEx: FailedRequestException => reqEx
          case ex => FailedRequestException("Test exception", ex, None, request)
        }
        client ! akka.actor.Status.Failure(wrappedEx)
    }
  }
}
