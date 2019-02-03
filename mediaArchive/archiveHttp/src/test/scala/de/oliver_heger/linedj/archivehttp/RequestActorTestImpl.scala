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

package de.oliver_heger.linedj.archivehttp

import java.io.IOException

import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.RequestActorTestImpl.{ExpectRequest, InitRequestResponseMapping}
import de.oliver_heger.linedj.archivehttp.config.{HttpArchiveConfig, UserCredentials}
import de.oliver_heger.linedj.archivehttp.impl.io.HttpRequestActor
import de.oliver_heger.linedj.archivehttp.impl.io.HttpRequestActor.{ResponseData, SendRequest}
import org.scalatest.Matchers

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object RequestActorTestImpl {
  /** The class of the HTTP request actor. */
  val ClsRequestActor: Class[_] = requestActorClass()

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
    * @return the test config
    */
  def createTestArchiveConfig(): HttpArchiveConfig =
    HttpArchiveConfig(archiveURI = "https://some.archive.org/data", archiveName = "test",
      credentials = UserCredentials("scott", "tiger"), processorCount = 1, processorTimeout = Timeout(1.minute),
      propagationBufSize = 100, maxContentSize = 1024, downloadBufferSize = 1000, downloadMaxInactivity = 10.seconds,
      downloadReadChunkSize = 8192, timeoutReadSize = 111, downloadConfig = null, metaMappingConfig = null,
      contentMappingConfig = null, requestQueueSize = 100)


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
  * In addition to a sequence of requests and responses, an instance can also
  * be configured with a map. Then the order in which requests arrive does not
  * matter. A map with requests is evaluated only if there are no (more)
  * expected requests.
  */
class RequestActorTestImpl extends Actor with Matchers {
  /** A queue with expected requests and their responses. */
  private var expectedRequests = Queue.empty[ExpectRequest]

  /** A map with requests and their responses. */
  private var requestResponseMapping = Map.empty[HttpRequest, HttpResponse]

  override def receive: Receive = {
    case er: ExpectRequest =>
      expectedRequests = expectedRequests.enqueue(er)

    case InitRequestResponseMapping(mapping) =>
      requestResponseMapping = mapping

    case SendRequest(request, data) if expectedRequests.nonEmpty =>
      val (reqData, q) = expectedRequests.dequeue
      expectedRequests = q
      request should be(reqData.request)
      reqData.response match {
        case Success(resp) =>
          sender() ! ResponseData(resp, data)
        case Failure(exception) =>
          sender() ! akka.actor.Status.Failure(exception)
      }

    case SendRequest(request, data) if expectedRequests.isEmpty =>
      requestResponseMapping get request match {
        case Some(response) =>
          sender() ! ResponseData(response, data)
        case None =>
          sender() ! akka.actor.Status.Failure(new IOException("Failed request to " + request.uri))
      }
  }
}
