/*
 * Copyright 2015-2017 The Developers Team.
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

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * A trait that simplifies sending single HTTP requests via an HTTP flow.
  *
  * This trait provides an utility method to send a single HTTP request,
  * assuming that a flow created by [[HttpFlowFactory]] is already available.
  * Basically, a stream with a single request element is created which is
  * passed through the flow. The future result of stream processing is then
  * returned to the caller.
  *
  * @tparam T the type of the data object used by the flow
  */
trait HttpRequestSupport[T] {
  /**
    * Sends the specified request via the given HTTP request flow and
    * returns a ''Future'' with the response. The ''Future'' is only
    * successful if a successful response was received from the server; both
    * processing errors, and failure responses cause the ''Future'' to fail.
    *
    * @param request the request to be sent
    * @param data    a data object that will be propagated by the flow
    * @param flow    the flow to execute the request
    * @param mat     the object to materialize the processing stream
    * @param ec      an ''ExecutionContext'' for future processing
    * @return the processing result as tuple of the response and the data
    *         object
    */
  def sendRequest(request: HttpRequest, data: T,
                  flow: Flow[(HttpRequest, T), (Try[HttpResponse], T), Any])
                 (implicit mat: Materializer, ec: ExecutionContext): Future[(HttpResponse, T)] =
    Source.single((request, data))
      .via(flow)
      .runWith(Sink.last[(Try[HttpResponse], T)])
      .map(f => (f._1.get, f._2))
        .map ( handleFailedResponse)

  /**
    * Checks whether the response is a failure. In this case, it is transformed
    * to a special ''FailedRequestException''. It is also necessary to discard
    * the body.
    *
    * @param t   the tuple with the response and the data object
    * @param mat the object to materialize streams
    * @return the transformed tuple
    */
  private def handleFailedResponse(t: (HttpResponse, T))(implicit mat: Materializer):
  (HttpResponse, T) = {
    if (!t._1.status.isSuccess()) {
      t._1.discardEntityBytes()
      throw FailedRequestException(t._1)
    }
    t
  }
}
