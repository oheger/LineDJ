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

package de.oliver_heger.linedj.archivehttp.spi

import java.io.IOException

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController.ParseFolderResult

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object UriResolverController {

  /**
    * A data class representing the result of a an operation to parse the
    * content of a folder.
    *
    * An instance of this class is returned by the function that interprets the
    * response of a folder request. Here the primary data is the list of name
    * with the elements contained in this folder. For some folders, however, a
    * paging mechanism is used for large folders. Then a single response does
    * not contain the full listing of the folder, but another request has to be
    * sent to fetch the next page. To support this, this class supports an
    * option with a next request that has to be sent.
    *
    * @param elements    a list with the element names contained in the folder
    * @param nextRequest an ''Option'' with the next request to be sent
    */
  case class ParseFolderResult(elements: List[String], nextRequest: Option[HttpRequests.SendRequest])

}

/**
  * A trait defining a number of operations that are required to resolve an URI
  * for an encrypted archive.
  *
  * If an archive is encrypted, a way is needed to transform a URI in plaintext
  * to an encrypted one. This is done by iterating over the folder structures
  * of the archive and decrypting the file and folder names. For this purpose,
  * support from the HTTP archive protocol is needed.
  *
  * This trait defines the protocol between the URI resolver component and the
  * HTTP protocol. The basic process is that at first the path components of
  * the URI to be resolved are determined. The resolver component then tries to
  * resolve each of them; for each component a request to the folder is
  * generated, and the response is parsed to obtain the names of the elements
  * in this folder. That way the decrypted path that corresponds to the plain
  * text path can be found. In a final step, the resolved path has to be
  * transformed to a full URI. The steps of this process correspond to the
  * operations defined by this trait.
  *
  * In addition, some basic functionality is implemented that can be used by
  * concrete implementations, such as conversions from path components to full
  * URIs.
  */
trait UriResolverController {
  /**
    * Returns a flag whether the resolve operation managed by this controller
    * should be skipped. This can be used by protocol implementations for URIs
    * that can be directly passed through; e.g. if a protocol uses special
    * download URIs. This base implementation returns '''false''', meaning that
    * a resolve operation is required.
    *
    * @return a flag whether the resolve operation should be skipped
    */
  def skipResolve: Boolean = false

  /**
    * Returns the relative path whose components need to be resolved. This
    * may fail if an unexpected URI to be resolved has been passed, e.g. one
    * that does not belong to the current archive. Note that the resulting path
    * MUST start with a slash.
    *
    * @return a ''Try'' with the relative path to be resolved
    */
  def extractPathToResolve(): Try[String]

  /**
    * Returns a request to query the content of a specific folder on the
    * server. This function is needed to list the names of files on the server.
    * The caller is responsible of the execution of the request. It will later
    * delegate to this object again to parse the response. The passed in
    * parameter is the relative path which needs to be requested in the current
    * step.
    *
    * @param path the relative path to the folder to be looked up
    * @return a request to query the content of this folder
    */
  def createFolderRequest(path: String): HttpRequest

  /**
    * Processes the given response of a request to query the content of a
    * folder and returns an object with the result. The object contains the
    * sequence with the names of the elements contained in this folder. The
    * resulting strings should be the pure element names without parent paths.
    * If the protocol supports paging, an ''Option'' with the request to
    * retrieve the next page can be provided.
    *
    * @param response the response to be processed
    * @param ec       the execution context
    * @param system   the actor system to materialize streams
    * @return a ''Future'' with the sequence of the element names in the folder
    */
  def extractNamesFromFolderResponse(response: HttpResponse)(implicit ec: ExecutionContext, system: ActorSystem):
  Future[ParseFolderResult]

  /**
    * Constructs the final URI from the resolved path. This method is called as
    * the last step of a resolve operation with the resolved path. An
    * implementation now needs to construct the final URI pointing to the file
    * affected.
    *
    * @param path the path that has been resolved
    * @return the final URI to the referenced media file
    */
  def constructResultUri(path: String): Uri

  /**
    * Checks whether the URI to be resolved starts with the expected base path.
    * If this is the case, a success result with the URI is returned;
    * otherwise, result is a failure with a corresponding exception.
    *
    * @param resolveUri the URI to be resolved
    * @param basePath   the base path
    * @return a ''Try'' with the checked URI
    */
  protected def checkBasePath(resolveUri: Uri, basePath: String): Try[Uri] =
    if (resolveUri.path.toString() startsWith basePath) Success(resolveUri)
    else Failure(new IOException(s"Invalid URI to resolve: $resolveUri does not start with base path $basePath."))

  /**
    * Converts the given URI to be resolved to a string and removes the base
    * path prefix. The result is the relative path which needs to be resolved.
    * Note that this function does not check whether the URI actually starts
    * with the base path; this has to be done manually using other functions.
    *
    * @param resolveUri the URI to be resolved
    * @param basePath   the base path
    * @return the relative path string with the base path removed
    */
  protected def removeBasePath(resolveUri: Uri, basePath: String): String =
    resolveUri.path.toString().substring(basePath.length)
}
