/*
 * Copyright 2015-2021 The Developers Team.
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

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController.ParseFolderResult
import de.oliver_heger.linedj.shared.archive.media.UriHelper

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.xml.{Elem, Node, NodeSeq, XML}

private object WebDavResolverController {
  /** Media type of the data that is expected from the server. */
  private val MediaXML = MediaRange(MediaType.text("xml"))

  /** The Accept header to be used by all requests. */
  private val HeaderAccept = Accept(MediaXML)

  /** The list of headers to include into a folder request. */
  private val FolderRequestHeaders = List(HeaderAccept, DepthHeader.DefaultDepthHeader)

  /** Constant for the custom HTTP method used to query folders. */
  private val MethodPropFind = HttpMethod.custom("PROPFIND")

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
    * @param system   the actor system to materialize streams
    * @return a future with the XML root element
    */
  private def parseFolderResponse(response: HttpResponse)
                                 (implicit ec: ExecutionContext, system: ActorSystem): Future[Elem] = {
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
  * An implementation of ''UriResolverController'' for the WebDav protocol.
  *
  * This class provides the functionality to generate requests for WebDav
  * folders and to parse the XML-based responses.
  *
  * @param uriToResolve the URI that is to be resolved
  * @param basePath     the relative base path of the archive
  */
private class WebDavResolverController(val uriToResolve: Uri, val basePath: String) extends UriResolverController {

  import WebDavResolverController._

  /**
    * @inheritdoc This implementation checks whether the path to be resolved
    *             starts with the base path. If so, the base path is removed.
    *             Otherwise, a failure is returned.
    */
  override def extractPathToResolve(): Try[String] =
    checkBasePath(uriToResolve, basePath) map (uri => removeBasePath(uri, basePath))

  /**
    * @inheritdoc This implementation uses the custom ''PROPFIND'' method to
    *             obtain the content of a WebDav folder.
    */
  override def createFolderRequest(path: String): HttpRequest =
    HttpRequest(method = MethodPropFind, headers = FolderRequestHeaders,
      uri = basePath + UriHelper.withTrailingSeparator(path))

  /**
    * @inheritdoc This implementation reads the response body and parses it as
    *             XML. Then the WebDav elements are iterated over to extract
    *             the names of the child items.
    */
  override def extractNamesFromFolderResponse(response: HttpResponse)
                                             (implicit ec: ExecutionContext, system: ActorSystem):
  Future[UriResolverController.ParseFolderResult] =
    parseFolderResponse(response) map extractNamesInFolder map { elems =>
      ParseFolderResult(elems.toList, None)
    }

  /**
    * @inheritdoc This implementation returns a relative URI consisting of the
    *             base path and the passed in resolved path.
    */
  override def constructResultUri(path: String): Uri =
    Uri(basePath + path)
}
