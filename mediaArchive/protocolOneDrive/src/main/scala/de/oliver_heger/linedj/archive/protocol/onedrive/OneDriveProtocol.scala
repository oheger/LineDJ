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

import akka.actor.ActorRef
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.shared.archive.media.UriHelper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object OneDriveProtocol {
  /** The name used by the OneDrive protocol. */
  val ProtocolName = "onedrive"

  /** The default root URI for the OneDrive API. */
  val OneDriveServerUri: Uri = Uri("https://graph.microsoft.com")

  /** The base path of the OneDrive API. */
  val OneDriveApiPath = "/v1.0/me/drives/"

  /** The suffix to be appended to request the content of a file. */
  private val SuffixContent = ":/content"

  /**
    * Generates the request to query the content URI of a file to be
    * downloaded.
    *
    * @param uri the URI of the file in question
    * @return the request
    */
  private def createContentRequest(uri: Uri): HttpRequests.SendRequest = {
    println("Content request for " + uri)
    val contentUri = generateItemsUri(uri + SuffixContent)
    println("Content URI is " + contentUri)
    val request = HttpRequest(uri = contentUri.resolvedAgainst(OneDriveServerUri))
    HttpRequests.SendRequest(request, null)
  }

  /**
    * Generates an URI to access the items resource from the passed in source
    * URI. Here the ''items'' keyword has to be added before the path starting
    * with ''/root''.
    *
    * @param srcUri the source URI
    * @return the resulting items URI
    */
  private def generateItemsUri(srcUri: String): Uri =
    srcUri.replace("/root:", "/items/root:")
}

/**
  * Implementation of the OneDrive protocol to be used by HTTP archives.
  *
  * This class implements the functionality required to load media files from a
  * OneDrive servers. In OneDrive, download operations are a bit more complex
  * because the download URI of a file has to be determined in a first request.
  * It typically points to another server. The download from this server then
  * has to be triggered explicitly.
  *
  * The other functionality provided by this protocol implementation is access
  * to folder listings. Here the JSON responses used by OneDrive need to be
  * parsed.
  */
class OneDriveProtocol extends HttpArchiveProtocol {

  import OneDriveProtocol._

  override val name: String = ProtocolName

  /**
    * @inheritdoc This implementation returns '''true''', as file downloads are
    *             typically served by another server.
    */
  override val requiresMultiHostSupport: Boolean = true

  /**
    * @inheritdoc This implementation expects a URI of the form
    *             ''driveId/path''. Out of these components it constructs an
    *             absolute OneDrive URI to query files in this path.
    */
  override def generateArchiveUri(sourceUri: String): Try[Uri] = {
    val posSeparator = sourceUri indexOf UriHelper.UriSeparatorChar
    if (posSeparator < 0) Failure(new IllegalArgumentException(s"Invalid archive URI '$sourceUri'. " +
      "URI must be of the form <driveID>/path"))
    else {
      val driveID = sourceUri.substring(0, posSeparator)
      val path = sourceUri.substring(posSeparator)
      val relArchiveUri = Uri(OneDriveApiPath + driveID + "/root:" + UriHelper.removeTrailingSeparator(path))
      Success(relArchiveUri.resolvedAgainst(OneDriveServerUri))
    }
  }

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
    * @param mat       the object to materialize streams
    * @param timeout   a timeout for sending the request
    * @return a ''Future'' with the response of the download request
    */
  override def downloadMediaFile(httpActor: ActorRef, uri: Uri)
                                (implicit ec: ExecutionContext, mat: ActorMaterializer, timeout: Timeout):
  Future[HttpRequests.ResponseData] =
    for {
      contentResult <- HttpRequests.discardEntityBytes(HttpRequests.sendRequest(httpActor,
        createContentRequest(uri)))
      downloadResponse <- sendDownloadRequest(httpActor, contentResult)
    } yield downloadResponse

  /**
    * Returns a request to query the content of a specific folder on the
    * server. This function is needed to list the names of files on the server.
    * The caller is responsible of the execution of the request. It will later
    * delegate to this object again to parse the response.
    *
    * @param path the relative path to the folder to be looked up
    * @return a request to query the content of this folder
    */
  override def createFolderRequest(baseUri: Uri, path: String): HttpRequest = ???

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
  override def extractNamesFromFolderResponse(response: HttpResponse)(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[Seq[String]] = ???

  /**
    * Sends the actual download request for a file. The download URI is
    * extracted from the ''Location'' header of the passed in result. Note
    * that the download URI is likely to refer to a different server;
    * therefore, the request actor cannot be used
    *
    * @param contentResult the result of the content request
    * @return a ''Future'' with the response of the download request
    */
  private def sendDownloadRequest(httpActor: ActorRef, contentResult: HttpRequests.ResponseData)
                                 (implicit timeout: Timeout): Future[HttpRequests.ResponseData] = {
    val location = contentResult.response.header[Location]
    val request = HttpRequest(uri = location.get.uri)
    HttpRequests.sendRequest(httpActor, HttpRequests.SendRequest(request, null))
  }
}
