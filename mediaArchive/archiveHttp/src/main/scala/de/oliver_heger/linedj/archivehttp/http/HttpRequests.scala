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

package de.oliver_heger.linedj.archivehttp.http

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}

/**
  * A module defining representations and functionality for sending HTTP
  * requests.
  *
  * HTTP requests are sent via actor references. This object defines the
  * messages used for this purpose. It also offers some functions that simplify
  * the interactions with HTTP actors.
  */
object HttpRequests {

  /**
    * A message triggering the sending of an HTTP request.
    *
    * The HTTP actor sends the given request to the target server. It then
    * returns a [[ResponseData]] object to the sender. When called via the
    * ''ask'' pattern the future fails if the request was not successful.
    *
    * @param request the request to be executed
    * @param data    additional request data that is included in the response
    */
  case class SendRequest(request: HttpRequest, data: Any)

  /**
    * A data class representing the response of a request.
    *
    * This class is used by the HTTP actor to send the result of a request
    * execution back to the sender.
    *
    * @param response the response from the server
    * @param data     additional request data
    */
  case class ResponseData(response: HttpResponse, data: Any)

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

  /**
    * Discards the bytes of the entity from the given result from an HTTP
    * actor. This function is useful for requests for which the entity is
    * irrelevant. (Nevertheless, it has to be dealt with to avoid blocking of
    * the HTTP stream.)
    *
    * @param result the result object
    * @param ec     the execution context
    * @param mat    the object to materialize streams
    * @return a ''Future'' of the result with the entity discarded
    */
  def discardEntityBytes(result: ResponseData)
                        (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[ResponseData] =
    result.response.entity.discardBytes().future().map(_ => result)

  /**
    * Discards the bytes of the entity from the given ''Future'' result from an
    * HTTP actor. Works like the method with the same name, but operates on the
    * result future rather than the actual result.
    *
    * @param futResult the ''Future'' with the result object
    * @param ec        the execution context
    * @param mat       the object to materialize streams
    * @return a ''Future'' of the result with the entity discarded
    */
  def discardEntityBytes(futResult: Future[ResponseData])
                        (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[ResponseData] =
    futResult flatMap discardEntityBytes
}
