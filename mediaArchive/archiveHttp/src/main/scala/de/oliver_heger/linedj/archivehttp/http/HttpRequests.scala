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

package de.oliver_heger.linedj.archivehttp.http

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

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
    * Header property that indicates that no decryption should be done on the
    * response entity.
    */
  val HeaderPropNoDecrypt = "no-decrypt"

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
    * @param system the actor system to materialize streams
    * @return a ''Future'' of the result with the entity discarded
    */
  def discardEntityBytes(result: ResponseData)
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[ResponseData] =
    result.response.entity.discardBytes().future().map(_ => result)

  /**
    * Discards the bytes of the entity from the given ''Future'' result from an
    * HTTP actor. Works like the method with the same name, but operates on the
    * result future rather than the actual result.
    *
    * @param futResult the ''Future'' with the result object
    * @param ec        the execution context
    * @param system    the actor system to materialize streams
    * @return a ''Future'' of the result with the entity discarded
    */
  def discardEntityBytes(futResult: Future[ResponseData])
                        (implicit ec: ExecutionContext, system: ActorSystem): Future[ResponseData] =
    futResult flatMap discardEntityBytes

  /**
    * Class representing a special header defining properties for the request
    * processing actors.
    *
    * The value of this header is a string that consists of an arbitrary number
    * of flag values. The actors processing the request check for the presence
    * or absence of specific flag values and adapt their behavior accordingly.
    *
    * @param valueStr the value of the header
    */
  class XRequestPropsHeader(valueStr: String) extends ModeledCustomHeader[XRequestPropsHeader] {
    override val companion: ModeledCustomHeaderCompanion[XRequestPropsHeader] = XRequestPropsHeader

    override def value(): String = valueStr

    override def renderInRequests(): Boolean = true

    override def renderInResponses(): Boolean = true
  }

  object XRequestPropsHeader extends ModeledCustomHeaderCompanion[XRequestPropsHeader] {
    override val name: String = "X-Request-Props"

    /**
      * Creates an instance of ''XRequestPropsHeader'' whose value consists of
      * the given properties.
      *
      * @param props the property values to construct the header value
      * @return the initialized header instance
      */
    def withProperties(props: String*): XRequestPropsHeader =
      new XRequestPropsHeader(props.mkString(";"))

    /**
      * Convenience function to check whether a header of this class exists in
      * the request that has the given property.
      *
      * @param request  the request
      * @param property the property to check for
      * @return a flag whether this request property exists
      */
    def hasRequestProperty(request: HttpRequest, property: String): Boolean =
      request.header[XRequestPropsHeader].exists(_.value().contains(property))

    override def parse(value: String): Try[XRequestPropsHeader] =
      Try(new XRequestPropsHeader(value))
  }

}
