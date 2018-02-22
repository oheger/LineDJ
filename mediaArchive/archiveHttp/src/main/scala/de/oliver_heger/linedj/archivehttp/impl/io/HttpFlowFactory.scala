/*
 * Copyright 2015-2018 The Developers Team.
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

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.http.scaladsl.{ClientTransport, Http}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import de.oliver_heger.linedj.utils.SystemPropertyAccess

import scala.util.Try

/**
  * A trait that handles the creation of a flow for sending HTTP requests.
  *
  * This trait encapsulates the flow creation operation to make client
  * classes easier to test.
  *
  * In addition to creating a flow with default parameters, the trait supports
  * the Java system properties ''http.proxyHost'' and ''http.proxyPort''. If
  * both of them are defined, the resulting flow is configured to use this
  * proxy server.
  */
trait HttpFlowFactory {
  this: SystemPropertyAccess =>

  def createHttpFlow[T](uri: Uri)(implicit mat: Materializer, system: ActorSystem):
  Flow[(HttpRequest, T), (Try[HttpResponse], T), Any] = {
    if (uri.scheme == "https")
      Http().cachedHostConnectionPoolHttps(host = uri.authority.host.address(),
        port = extractPort(uri, 443), settings = fetchSettings(system))
    else
      Http().cachedHostConnectionPool[T](host = uri.authority.host.address(),
        port = extractPort(uri, 80), settings = fetchSettings(system))
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

  /**
    * Obtains the setting for the HTTP flow. This method checks for certain
    * system properties to configure the connection, such as proxy settings.
    *
    * @param system the actor system
    * @return settings for the connection pool
    */
  private def fetchSettings(system: ActorSystem): ConnectionPoolSettings =
    ConnectionPoolSettings(system).withTransport(fetchClientTransport(fetchProxyParams()))

  /**
    * Returns an ''Option'' with proxy parameters obtained from system
    * properties. This method checks whether the typical Java system
    * properties are set that are used to specify a proxy. If so, a tuple with
    * the proxy host and port is returned.
    *
    * @return an ''Option'' with proxy host and port
    */
  private def fetchProxyParams(): Option[(String, Int)] = for {
    proxyHost <- getSystemProperty("http.proxyHost")
    proxyPort <- getSystemProperty("http.proxyPort")
  } yield (proxyHost, proxyPort.toInt)

  /**
    * Returns a ''ClientTransport'' object depending on the availability of a
    * proxy definition.
    *
    * @param proxySettings an ''Option'' with parameters for a proxy
    * @return a ''ClientTransport'' to be used for the current setup
    */
  private def fetchClientTransport(proxySettings: Option[(String, Int)]): ClientTransport =
    proxySettings.map(s =>
      ClientTransport.httpsProxy(InetSocketAddress.createUnresolved(s._1, s._2)))
      .getOrElse(ClientTransport.TCP)
}
