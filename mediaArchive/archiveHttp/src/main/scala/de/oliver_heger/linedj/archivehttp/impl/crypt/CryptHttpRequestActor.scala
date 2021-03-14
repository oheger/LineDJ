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

package de.oliver_heger.linedj.archivehttp.impl.crypt

import java.security.{Key, SecureRandom}

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.pattern.ask
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.{ResponseData, SendRequest, XRequestPropsHeader}
import de.oliver_heger.linedj.archivehttp.impl.crypt.CryptHttpRequestActor.CryptRequestData
import de.oliver_heger.linedj.archivehttp.impl.crypt.UriResolverActor.{ResolveUri, ResolvedUri}
import de.oliver_heger.linedj.archivehttp.impl.io.FailedRequestException

import scala.util.{Failure, Success}

object CryptHttpRequestActor {

  /**
    * An internally used data class that collects the data required for the
    * processing of a request.
    *
    * @param caller     the caller
    * @param orgRequest the original (plain) request
    */
  private case class CryptRequestData(caller: ActorRef, orgRequest: SendRequest)

}

/**
  * An actor class that replaces the normal ''HttpRequestActor'' for encrypted
  * archives.
  *
  * Like ''HttpRequestActor'', this actor processes messages corresponding to
  * HTTP requests. However, request processing differs in the following ways:
  *
  *  - The request URIs reference plain file names; therefore, they have to be
  * resolved first to point to the corresponding encrypted file paths. This is
  * done with the help of [[UriResolverActor]].
  *  - The entities of responses to download media files are replaced, so that
  * the file content is decrypted on the fly. So the receiver sees the plain
  * media data.
  *
  * Actual request processing is delegated to the request actor passed to the
  * constructor.
  *
  * @param resolverActor  the actor to resolve URIs
  * @param requestActor   the actor to execute HTTP requests
  * @param key            the key to be used for decryption
  * @param resolveTimeout timeout for resolve operations
  */
class CryptHttpRequestActor(resolverActor: ActorRef, requestActor: ActorRef, key: Key, resolveTimeout: Timeout)
  extends Actor with ActorLogging {
  /** Implicit timeout for invocations of the resolve actor. */
  private implicit val timeout: Timeout = resolveTimeout

  /** The object to generate random numbers. */
  private implicit val secRandom: SecureRandom = new SecureRandom

  import context.dispatcher

  override def receive: Receive = {
    case msg: SendRequest =>
      handleHttpRequest(msg)

    case ResponseData(response, data@CryptRequestData(_, _)) =>
      handleHttpResponse(response, data)

    case akka.actor.Status.Failure(cause@FailedRequestException(_, _, _,
    SendRequest(_, CryptRequestData(caller, orgRequest)))) =>
      sendErrorResponse(caller, cause.copy(request = orgRequest))
  }

  /**
    * Handles a ''SendRequest'' message. The requested URI is resolved, then
    * the new request is passed to the request actor.
    *
    * @param msg the message to send a request
    */
  private def handleHttpRequest(msg: SendRequest): Unit = {
    val requestData = CryptRequestData(sender(), msg)
    (resolverActor ? ResolveUri(msg.request.uri)).mapTo[ResolvedUri] onComplete {
      case Success(result) =>
        val resolvedRequest = msg.request.copy(uri = result.resolvedUri)
        requestActor ! SendRequest(resolvedRequest, requestData)
      case Failure(exception) =>
        log.error(exception, "Failed to resolve URI " + msg.request.uri)
        val reqException = FailedRequestException("Could not resolve URI", exception, None,
          requestData.orgRequest)
        sendErrorResponse(requestData.caller, reqException)
    }
  }

  /**
    * Handles a response from the underlying HTTP request actor. The entity of
    * the response is modified to decrypt its content. The modified response is
    * then sent to the caller.
    *
    * @param response the response from the underlying request actor
    * @param data     the data object describing the operation
    */
  private def handleHttpResponse(response: HttpResponse, data: CryptRequestData): Unit = {
    val resultResponse =
      if (XRequestPropsHeader.hasRequestProperty(data.orgRequest.request, HttpRequests.HeaderPropNoDecrypt))
        response
      else {
        val plainEntity = HttpEntity(response.entity.contentType,
          CryptService.decryptSource(key, response.entity.dataBytes))
        response.copy(entity = plainEntity)
      }
    data.caller ! ResponseData(resultResponse, data.orgRequest.data)
  }

  /**
    * Sends an error response with the given cause to the given receiver.
    *
    * @param receiver  the receiver
    * @param exception the exception to be sent
    */
  private def sendErrorResponse(receiver: ActorRef, exception: Throwable): Unit = {
    receiver ! akka.actor.Status.Failure(exception)
  }
}
