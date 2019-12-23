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

package de.oliver_heger.linedj.archive.protocol.onedrive

import java.io.IOException

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.SendRequest
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController.ParseFolderResult
import de.oliver_heger.linedj.shared.archive.media.UriHelper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

private object OneDriveResolverController {
  /** The suffix to be appended to request the content of a file. */
  val SuffixContent = ":/content"

  /** The prefix for the items resource in download URIs. */
  val PrefixItems = "/items"

  /** The root prefix signalling the beginning of a relative path. */
  val PrefixRoot = "/root:"

  /** Media type of the data that is expected from the server. */
  private val MediaJson = MediaRange(MediaType.applicationWithFixedCharset("json", HttpCharsets.`UTF-8`))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaJson)

  /** List with the headers sent for each folder request. */
  private val FolderRequestHeaders = List(HeaderAccept)

  /** The suffix required to obtain the child elements of a folder path. */
  private val SuffixChildren = ":/children"

  /**
    * Transforms the JSON representation of a folder listing to the result
    * object that is expected from the protocol.
    *
    * @param model the JSON model of the folder listing
    * @return the corresponding ''ParseFolderResult''
    */
  private def createParseResultFor(model: OneDriveModel): ParseFolderResult =
    ParseFolderResult(elements = model.value.map(_.name),
      nextRequest = model.nextLink map createFolderSendRequestForUri)

  /**
    * Creates a ''SendRequest'' object to query the content of the folder
    * defined by the given URI.
    *
    * @param uri hte URI pointing to the folder
    * @return the ''SendRequest'' to query the content of this folder
    */
  private def createFolderSendRequestForUri(uri: String): SendRequest =
    SendRequest(createFolderRequestForUri(uri), null)

  /**
    * Creates a request for the content of the folder defined by the given URI.
    *
    * @param uri the URI pointing to the folder
    * @return the request to query the content of this folder
    */
  private def createFolderRequestForUri(uri: Uri): HttpRequest =
    HttpRequest(uri = uri, headers = FolderRequestHeaders)

  /**
    * Computes the root URI for folder requests based on the base URI of the
    * associated archive.
    *
    * @param baseUri the archive's base URI
    * @return the root URI for folder requests
    */
  private def folderRootUri(baseUri: String): String =
    baseUri.replace(PrefixItems + PrefixRoot, PrefixRoot)

  /**
    * Removes the content suffix from a path to be resolved. If the path does
    * not end with this suffix, a failure is returned.
    *
    * @param path the path to be checked
    * @return a ''Try'' with the path without the content suffix
    */
  private def removeContentSuffix(path: String): Try[String] =
    if (path.endsWith(SuffixContent)) Success(path.substring(0, path.length - SuffixContent.length))
    else Failure(new IOException(s"Path to be resolved '$path' does not end with suffix '$SuffixContent'."))
}

/**
  * OneDrive-specific implementation of the ''UriResolverController'' trait.
  *
  * This class implements the functionality to request the content of OneDrive
  * folders and parse their JSON-based content listings. The URIs that can be
  * resolved by this class must be valid OneDrive download URIs as generated by
  * the protocol implementation. The direct download URIs (obtained via a
  * content request to a media file) are passed through using the skip flag.
  *
  * @param uriToResolve the URI that should be resolved
  * @param basePath     the base path of the protocol
  */
private class OneDriveResolverController(val uriToResolve: Uri, val basePath: String) extends UriResolverController {

  import OneDriveResolverController._

  /** Stores the root path of all folder requests. */
  private val folderRootPath = folderRootUri(basePath)

  /**
    * @inheritdoc This implementation checks whether the path of the URI to be
    *             resolved ends with the suffix for content requests.
    */
  override def skipResolve: Boolean =
    !uriToResolve.path.toString().endsWith(SuffixContent)

  /**
    * @inheritdoc This implementation returns the part of the URI to be
    *             resolved after the base path and without the content suffix.
    */
  override def extractPathToResolve(): Try[String] =
    checkBasePath(uriToResolve, basePath)
      .map(uri => removeBasePath(uri, basePath))
      .flatMap(removeContentSuffix)

  /**
    * @inheritdoc This implementation generates a correct folder URI based on
    *             the root path of all folders with the :/children suffix.
    */
  override def createFolderRequest(path: String): HttpRequest = {
    val uri = uriToResolve.withPath(Path(folderRootPath + UriHelper.removeTrailingSeparator(path) + SuffixChildren))
    createFolderRequestForUri(uri)
  }

  /**
    * @inheritdoc This implementation does a JSON de-serialization of the
    *             response entity and extracts the element names from the JSON
    *             model representation.
    */
  override def extractNamesFromFolderResponse(response: HttpResponse)
                                             (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[ParseFolderResult] = {
    import OneDriveJsonProtocol._
    val model = Unmarshal(response).to[OneDriveModel]
    model map createParseResultFor
  }

  /**
    * @inheritdoc This implementation adds the base path and the content suffix
    *             to the resolved path; the host from the original request is
    *             used.
    */
  override def constructResultUri(path: String): Uri =
    uriToResolve.withPath(Path(basePath + path + SuffixContent))
}
