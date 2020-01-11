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

package de.oliver_heger.linedj.archive.protocol.webdav

import akka.http.scaladsl.model._
import de.oliver_heger.linedj.archivehttp.spi.{HttpArchiveProtocol, UriResolverController}

object WebDavProtocol {
  /** The name to be returned for this protocol. */
  private val WebDavProtocolName = "webdav"
}

/**
  * Implementation of the WebDav protocol to be used by HTTP archives.
  *
  * This class implements functionality to query and download files from a
  * WebDav server. The WebDav protocol makes use of XML to represent folder
  * structures on the server; these have to be parsed. Download requests,
  * however, are straight-forward.
  */
class WebDavProtocol extends HttpArchiveProtocol {

  import WebDavProtocol._

  override val name: String = WebDavProtocolName

  /**
    * @inheritdoc All WebDav operations target the same host; so this
    *             implementation returns '''false'''.
    */
  override val requiresMultiHostSupport: Boolean = false

  override def resolveController(requestUri: Uri, basePath: String): UriResolverController =
    new WebDavResolverController(requestUri, basePath)
}
