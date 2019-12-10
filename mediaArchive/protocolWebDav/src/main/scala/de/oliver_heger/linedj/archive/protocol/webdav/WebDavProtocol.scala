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

package de.oliver_heger.linedj.archive.protocol.webdav

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.spi.HttpArchiveProtocol
import de.oliver_heger.linedj.shared.archive.media.UriHelper

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, Node, NodeSeq, XML}

object WebDavProtocol {
  /** Media type of the data that is expected from the server. */
  private val MediaXML = MediaRange(MediaType.text("xml"))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaXML)

  /** The list of headers to include into a folder request. */
  private val FolderRequestHeaders = List(HeaderAccept, DepthHeader.DefaultDepthHeader)

  /** Constant for the custom HTTP method used to query folders. */
  private val MethodPropFind = HttpMethod.custom("PROPFIND")

  /** The name to be returned for this protocol. */
  private val WebDavProtocolName = "webdav"

  /** Name of the XML response element. */
  private val ElemResponse = "response"

  /** Name of the XML href element. */
  private val ElemHref = "href"

  /**
    * Processes a response for a folder request. The entity is read and parsed
    * to an XML element.
    *
    * @param response the response
    * @param ec       the execution context
    * @param mat      the object to materialize streams
    * @return a future with the XML root element
    */
  private def parseFolderResponse(response: HttpResponse)
                                 (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[Elem] = {
    val sink = Sink.fold[ByteString, ByteString](ByteString.empty)(_ ++ _)
    response.entity.dataBytes.runWith(sink).map { body =>
      val stream = new ByteArrayInputStream(body.toArray)
      XML.load(stream)
    }
  }

  /**
    * Processes an XML document for the content of a WebDav folder and extracts
    * all the names for files and sub folders from it.
    *
    * @param elem the XML root element for the folder document
    * @return a list with all extracted element names
    */
  private def extractNamesInFolder(elem: Elem): Seq[String] =
    (elem \ ElemResponse).drop(1) // first element is the folder itself
      .map(extractElementName)

  /**
    * Extracts the name of an element contained in a folder from the given XML
    * node. The text content of the node consists of the full path of the
    * element. Here the last path component (the name) is extracted.
    *
    * @param node the current XML node
    * @return the element name extracted
    */
  private def extractElementName(node: Node): String = {
    val path = UriHelper.removeTrailingSeparator(elemText(node, ElemHref))
    UriHelper.extractName(path)
  }

  /**
    * Extracts the text of a sub element of the given XML node. Handles line
    * breaks in the element.
    *
    * @param node     the node representing the parent element
    * @param elemName the name of the element to be obtained
    * @return the text of this element
    */
  private def elemText(node: NodeSeq, elemName: String): String =
    removeLF((node \ elemName).text)

  /**
    * Removes new line and special characters from the given string. Also
    * handles the case that indention after a new line will add additional
    * whitespace; this is collapsed to a single space.
    *
    * @param s the string to be processed
    * @return the string with removed line breaks
    */
  private def removeLF(s: String): String =
    trimMultipleSpaces(s.map(c => if (c < ' ') ' ' else c)).trim

  /**
    * Replaces multiple space characters in a sequence in the given string by a
    * single one.
    *
    * @param s the string to be processed
    * @return the processed string
    */
  @tailrec private def trimMultipleSpaces(s: String): String = {
    val pos = s.indexOf("  ")
    if (pos < 0) s
    else {
      val s1 = s.substring(0, pos + 1)
      val s2 = s.substring(pos).dropWhile(_ == ' ')
      trimMultipleSpaces(s1 + s2)
    }
  }
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

  /**
    * @inheritdoc This implementation uses the custom ''PROPFIND'' method to
    *             obtain the content of a WebDav folder.
    */
  override def createFolderRequest(baseUri: Uri, path: String): HttpRequest =
    HttpRequest(method = MethodPropFind, headers = FolderRequestHeaders,
      uri = UriHelper.withTrailingSeparator(path))

  /**
    * @inheritdoc This implementation reads the response body and parses it as
    *             XML. Then the WebDav elements are iterated over to extract
    *             the names of the child items.
    */
  override def extractNamesFromFolderResponse(response: HttpResponse)
                                             (implicit ec: ExecutionContext, mat: ActorMaterializer):
  Future[Seq[String]] =
    parseFolderResponse(response) map extractNamesInFolder
}
