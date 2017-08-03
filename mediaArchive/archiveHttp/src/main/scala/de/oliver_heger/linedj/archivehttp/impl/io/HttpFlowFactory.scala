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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow

import scala.util.Try

/**
  * A trait that handles the creation of a flow for sending HTTP requests.
  *
  * This trait encapsulates the flow creation operation to make client
  * classes easier to test.
  */
trait HttpFlowFactory {
  def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, system: ActorSystem):
  Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
    if (uri.scheme == "https")
      Http().cachedHostConnectionPoolHttps(host = uri.authority.host.address(),
        port = extractPort(uri, 443))
    else
      Http().cachedHostConnectionPool[T](host = uri.authority.host.address(),
        port = extractPort(uri, 80))
  }

  /**
    * Extracts the port from a URI. If the port is defined, it is used.
    * Otherwise the provided default port is returned.
    *
    * @param uri         the URI
    * @param defaultPort the default port
    * @return the extracted port
    */
  def extractPort(uri: Uri, defaultPort: Int): Int =
    if (uri.authority.port != 0) uri.authority.port
    else defaultPort
}
