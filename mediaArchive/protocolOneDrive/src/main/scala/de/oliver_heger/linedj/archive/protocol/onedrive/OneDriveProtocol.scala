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

package de.oliver_heger.linedj.archive.protocol.onedrive

import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.XRequestPropsHeader
import de.oliver_heger.linedj.archivehttp.spi.{HttpArchiveProtocol, UriResolverController}
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

  /** The list of headers to be added to the content request. */
  val ContentHeaders = List(XRequestPropsHeader.withProperties(HttpRequests.HeaderPropNoDecrypt))

  /**
    * Generates the request to query the content URI of a file to be
    * downloaded. Here a special request property needs to be set to avoid that
    * the response is decrypted (which would cause an error).
    *
    * @param uri the absolute URI of the file in question
    * @return the request
    */
  private def createContentRequest(uri: Uri): HttpRequests.SendRequest = {
    val request = HttpRequest(uri = uri + OneDriveResolverController.SuffixContent, headers = ContentHeaders)
    HttpRequests.SendRequest(request, null)
  }

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
      val relArchiveUri = Uri(OneDriveApiPath + driveID + OneDriveResolverController.PrefixItems +
        OneDriveResolverController.PrefixRoot + UriHelper.removeTrailingSeparator(path))
      Success(relArchiveUri.resolvedAgainst(OneDriveServerUri))
    }
  }

  /**
    * @inheritdoc This function implements the 2-step download process required
    *             by OneDrive: requesting the download URI of the file in
    *             question, and then executing the download.
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

  /**
    * @inheritdoc This implementation returns an instance of a
    *             OneDrive-specific controller class.
    */
  override def resolveController(requestUri: Uri, basePath: String): UriResolverController =
    new OneDriveResolverController(requestUri, basePath)
}
