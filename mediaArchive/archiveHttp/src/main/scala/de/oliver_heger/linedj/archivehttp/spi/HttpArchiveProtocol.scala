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

package de.oliver_heger.linedj.archivehttp.spi

import akka.actor.ActorRef
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.http.HttpRequests

import scala.concurrent.{ExecutionContext, Future}

/**
  * A trait defining an SPI to plug in different HTTP-based protocols to be
  * used for media archives.
  *
  * The purpose of this trait is to abstract over the concrete access to media
  * files. It should then be possible to host such files on various servers,
  * e.g. WebDav servers, Microsoft OneDrive, Google Drive, etc.
  *
  * As the archive management only requires a limited interaction with media
  * files (access is only read-only), the operations defined here are not too
  * complex.
  */
trait HttpArchiveProtocol {
  /**
    * Returns a name for the represented protocol. The name is used to find a
    * corresponding protocol implementation for a specific media archive.
    *
    * @return the protocol name
    */
  def name: String

  /**
    * Returns a flag whether this protocol implementation needs to access
    * multiple hosts. For instance, files could be downloaded from a different
    * server than the one that serves API calls. If this method returns
    * '''true''', an HTTP actor supporting multiple hosts is created for the
    * associated archive.
    *
    * @return a flag whether support for multi hosts is needed
    */
  def requiresMultiHostSupport: Boolean

  /**
    * Handles a request to download a specific media file. The passed in HTTP
    * actor can be used to send the request. This base implementation sends a
    * direct GET request for the given URI. If a concrete protocol requires
    * some extra steps (e.g. obtaining the download URI first), these have to
    * be implemented here. In order to actually download the file, the entity
    * in the response is processed.
    *
    * @param httpActor the HTTP request actor for sending requests
    * @param uri       the URI pointing to the media file
    * @param ec        the execution context
    * @param timeout   a timeout for sending the request
    * @return a ''Future'' with the response of the download request
    */
  def downloadMediaFile(httpActor: ActorRef, uri: Uri)(implicit ec: ExecutionContext, timeout: Timeout):
  Future[HttpRequests.ResponseData] = {
    val request = HttpRequest(uri = uri)
    HttpRequests.sendRequest(httpActor, HttpRequests.SendRequest(request, null))
  }

  /**
    * Returns a request to query the content of a specific folder on the
    * server. This function is needed to list the names of files on the server.
    * The caller is responsible of the execution of the request. It will later
    * delegate to this object again to parse the response.
    *
    * @param path the relative path to the folder to be looked up
    * @return a request to query the content of this folder
    */
  def createFolderRequest(path: String): HttpRequest

  /**
    * Processes the given response of a request to query the content of a
    * folder and returns a sequence with the names of the elements contained in
    * this folder. The resulting strings should be the pure element names
    * without parent paths.
    *
    * @param response the response to be processed
    * @param ec       the execution context
    * @param mat      the object to materialize streams
    * @return a ''Future'' with the sequence of the element names in the folder
    */
  def extractNamesFromFolderResponse(response: HttpResponse)(implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[Seq[String]]
}
