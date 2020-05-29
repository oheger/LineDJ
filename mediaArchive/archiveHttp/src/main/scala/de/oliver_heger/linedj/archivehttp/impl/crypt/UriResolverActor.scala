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

package de.oliver_heger.linedj.archivehttp.impl.crypt

import java.io.IOException
import java.security.{Key, SecureRandom}

import akka.actor.{Actor, ActorRef}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.util.Timeout
import de.oliver_heger.linedj.archivehttp.http.HttpRequests
import de.oliver_heger.linedj.archivehttp.http.HttpRequests.SendRequest
import de.oliver_heger.linedj.archivehttp.spi.UriResolverController.ParseFolderResult
import de.oliver_heger.linedj.archivehttp.spi.{HttpArchiveProtocol, UriResolverController}
import de.oliver_heger.linedj.shared.archive.media.UriHelper
import de.oliver_heger.linedj.utils.LRUCache

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object UriResolverActor {

  /**
    * Request message to be processed by [[UriResolverActor]] that defines a
    * URI to be resolved.
    *
    * @param uri the URI
    */
  case class ResolveUri(uri: Uri)

  /**
    * A message sent by [[UriResolverActor]] as a response of a resolve
    * request. Note that the resolved URI is always relative.
    *
    * @param resolvedUri the resolved URI
    * @param originalUri the original URI
    */
  case class ResolvedUri(resolvedUri: Uri, originalUri: Uri)

  /**
    * An internally used message class to send the result of a folder request
    * to this actor.
    *
    * @param content    the content of the folder (encrypted and plain)
    * @param folderPath the path of the folder whose content was retrieved
    * @param orgPath    the original path of the folder (plain)
    */
  private case class FolderResultArrived(content: Map[String, String],
                                         folderPath: String,
                                         orgPath: String)

  /**
    * A data class that stores information about a resolve operation.
    *
    * @param client         the client of the request
    * @param controller     the resolve controller
    * @param resolvedPath   the part of the path that is already resolved
    * @param orgPath        the current original path (as plain text)
    * @param pathsToResolve the remaining parts that need to be resolved
    * @param resolveRequest the original request
    */
  private case class ResolveData(client: ActorRef,
                                 controller: UriResolverController,
                                 resolvedPath: String,
                                 orgPath: String,
                                 pathsToResolve: List[String],
                                 resolveRequest: ResolveUri) {
    /**
      * Returns a flag whether this resolve operation is complete. If this is
      * the case, the ''resolvedPath'' property contains the full resolved
      * path.
      *
      * @return '''true''' if this operation is complete, '''false''' otherwise
      */
    def resolved: Boolean = pathsToResolve.isEmpty

    /**
      * Sends a response with the result of this resolve operation to the
      * client. Note that the correct result is sent only if ''resolved''
      * returns '''true'''.
      */
    def sendResponse(): Unit = {
      client ! ResolvedUri(controller.constructResultUri(resolvedPath), resolveRequest.uri)
    }

    /**
      * Sends a response with the given error to the client of this operation.
      *
      * @param exception the exception to be sent
      */
    def sendErrorResponse(exception: Throwable): Unit = {
      client ! akka.actor.Status.Failure(exception)
    }

    /**
      * Updates the state of this resolve operation when data about the current
      * folder is available. This function tries to find the encrypted name of
      * the current part that is processed. If successful, an updated data
      * object is returned with the next part as current element (or a complete
      * one). If the current part cannot be resolved, a failure is returned.
      *
      * @param content the map with the content of the current folder
      * @return a ''Try'' with an updated data object
      */
    def updateWithFolderResponse(content: Map[String, String]): Try[ResolveData] =
      if (content.contains(pathsToResolve.head))
        Success(copy(resolvedPath = UriHelper.withLeadingSeparator(
          UriHelper.concat(resolvedPath, content(pathsToResolve.head))),
          orgPath = UriHelper.concat(orgPath, pathsToResolve.head),
          pathsToResolve = pathsToResolve.tail))
      else Failure(new IOException(s"Cannot resolve ${pathsToResolve.head} in $resolvedPath"))

  }

}

/**
  * An actor class that resolves URIs against an encrypted archive.
  *
  * When interacting with an archive whose file and folder names are encrypted
  * requests cannot be executed directly. This is because request URIs
  * reference the plain file names - which do not exist in this form on the
  * server. So the names in the requested path have to be replaced by their
  * encrypted counter parts.
  *
  * The encryption scheme used applies a random initialization vector to the
  * AES cipher. So each encrypt operation yields different output, even if the
  * input is the same. Therefore, it is not possible to simply map requested
  * URIs by encrypting them with the correct key. Rather, the folder structures
  * from the server have to be queried and decrypted and then matched against
  * the requested paths.
  *
  * This algorithm is implemented by this actor class and applied to all URI
  * path components after the provided ''basePath''. To avoid sending
  * repeated requests to the server, a cache of URIs already resolved is
  * managed; the size of this cache is configurable.
  *
  * The requests to fetch the content of folders on the server and the parsing
  * of their results are delegated to the [[HttpArchiveProtocol]] used for the
  * current archive.
  *
  * @param requestActor the actor for sending requests to the archive
  * @param protocol     the HTTP-based protocol used by the archive
  * @param decryptKey   the key to be used for decryption
  * @param basePath     the base path after which names are encrypted
  * @param uriCacheSize the size of the cache for URIs already resolved
  */
class UriResolverActor(requestActor: ActorRef, protocol: HttpArchiveProtocol, decryptKey: Key,
                       basePath: String, uriCacheSize: Int) extends Actor {

  import UriResolverActor._
  import context.{dispatcher, system}

  /** The secure random instance. */
  private implicit val secRandom: SecureRandom = new SecureRandom

  /**
    * A timeout value for messages sent to the request actor. This is needed
    * for the ask pattern. The request actor should always send a response, at
    * least a failure. So here a pretty high value is set.
    */
  private implicit val timeout: Timeout = Timeout(1.minute)

  /** A cache for storing already resolved URIs. */
  private val resolvedCache = new LRUCache[String, String](uriCacheSize)()

  /**
    * A map with resolve operations in progress grouped by the paths requested
    * from the server. This is used to prevent concurrent requests to the same
    * folder.
    */
  private var pendingRequests = Map.empty[String, List[ResolveData]]

  override def receive: Receive = {
    case req: ResolveUri =>
      val resolveController = protocol.resolveController(req.uri, basePath)
      if (resolveController.skipResolve)
        sender() ! ResolvedUri(req.uri, req.uri)
      else
        resolveController.extractPathToResolve() match {
          case Success(resolvePath) =>
            continueResolveOperation(createResolveData(resolveController, req, resolvePath))
          case Failure(exception) =>
            sender() ! akka.actor.Status.Failure(exception)
        }

    case FolderResultArrived(content, folderPath, orgPath) =>
      updateResolvedCache(content, folderPath, orgPath)
      continuePendingOperations(content, folderPath)
  }

  /**
    * Updates the cache with resolved paths with the content of a folder. This
    * method is called when a request for a folder's content has been
    * successfully processed.
    *
    * @param content    the map with the folder's content (encrypted and plain)
    * @param folderPath the path of this folder
    * @param orgPath    the original (plain) path of this folder
    */
  private def updateResolvedCache(content: Map[String, String], folderPath: String, orgPath: String): Unit = {
    val prefixResolved = UriHelper.withTrailingSeparator(folderPath)
    val prefixOrg = UriHelper.withTrailingSeparator(orgPath)
    content foreach { e =>
      resolvedCache.addItem(prefixOrg + e._1, prefixResolved + e._2)
    }
  }

  /**
    * Continues all resolve operations that were waiting for the content of a
    * specific folder.
    *
    * @param content    the map with the folder's content (encrypted and plain)
    * @param folderPath the path of this folder
    */
  private def continuePendingOperations(content: Map[String, String], folderPath: String): Unit = {
    val currentResolveOps = pendingRequests(folderPath)
    pendingRequests -= folderPath
    currentResolveOps foreach { resolveData =>
      resolveData.updateWithFolderResponse(content) match {
        case Success(nextData) =>
          continueResolveOperation(nextData)
        case Failure(exception) =>
          resolveData.sendErrorResponse(exception)
      }
    }
  }

  /**
    * Creates an initial ''ResolveData'' object for the passed in parameters.
    * Determines which parts of the requested URI need to be resolved.
    *
    * @param controller  the resolve controller
    * @param request     the current resolve request
    * @param resolvePath the path to be resolved
    * @return the ''ResolveData'' object for this request
    */
  private def createResolveData(controller: UriResolverController, request: ResolveUri, resolvePath: String):
  ResolveData = {
    @tailrec def calcUnresolvedComponents(path: String, unresolvedParts: List[String]):
    (String, String, List[String]) =
      if (path == basePath) (path, path, unresolvedParts)
      else if (resolvedCache.contains(path))
        (resolvedCache.get(path).get, path, unresolvedParts)
      else {
        val (parent, part) = UriHelper.splitParent(path)
        calcUnresolvedComponents(parent, part :: unresolvedParts)
      }

    val uri = UriHelper decodeComponents resolvePath
    val (resolved, org, unresolved) = calcUnresolvedComponents(basePath + uri, Nil)
    val resolvedPath = UriHelper.removeLeading(resolved, basePath)
    ResolveData(sender(), controller, resolvedPath, org, unresolved, request)
  }

  /**
    * Triggers the next step of a resolve operation. If the operation is
    * complete, the result is sent to the client. Otherwise, the next part of
    * the requested URI is resolved.
    *
    * @param resolveData the data object for the current resolve operation
    */
  private def continueResolveOperation(resolveData: ResolveData): Unit = {
    if (resolveData.resolved) resolveData.sendResponse()
    else {
      val folderRequests = pendingRequests.getOrElse(resolveData.resolvedPath, List.empty)
      pendingRequests += resolveData.resolvedPath -> (resolveData :: folderRequests)
      if (folderRequests.isEmpty) {
        val request = createFolderRequest(resolveData)
        (for {names <- processFolderRequest(resolveData.controller, request, Nil)
              nameMapping <- decryptElementNames(names)
              } yield nameMapping) onComplete {
          case Success(value) =>
            self ! FolderResultArrived(value, resolveData.resolvedPath, resolveData.orgPath)
          case Failure(exception) =>
            resolveData.sendErrorResponse(exception)
        }
      }
    }
  }

  /**
    * Sends the request for the content of the current folder in the resolve
    * operation and processes the result. The names of the elements contained
    * in the folder are extracted. If another request needs to be sent (if the
    * protocol supports paging), this is done, and the results are aggregated.
    *
    * @param controller the resolve controller
    * @param request    the request to be sent
    * @param content    the list of element names extracted so far
    * @return a list with the names of all elements contained in the folder
    */
  private def processFolderRequest(controller: UriResolverController, request: SendRequest, content: List[String]):
  Future[List[String]] = {
    sendFolderRequest(request) flatMap (parseFolderResponse(controller, _)) flatMap { parseResult =>
      val nextContent = parseResult.elements ::: content
      parseResult.nextRequest.fold(Future.successful(nextContent)) { nextReq =>
        processFolderRequest(controller, nextReq, nextContent)
      }
    }
  }

  /**
    * Sends a single request for the content of a folder and returns the
    * response from the server.
    *
    * @param request the request
    * @return a ''Future'' with the response
    */
  private def sendFolderRequest(request: SendRequest): Future[HttpResponse] =
    HttpRequests.sendRequest(requestActor, request).map(_.response)

  /**
    * Generates the request for the content of the folder to be resolved. The
    * folder is determined by the current state of the given resolve operation.
    *
    * @param data the data object for the current resolve operation
    * @return the request to read the content of the current folder
    */
  private def createFolderRequest(data: ResolveData): SendRequest = {
    val uri = UriHelper.withTrailingSeparator(data.resolvedPath)
    SendRequest(data.controller.createFolderRequest(uri), 0)
  }

  /**
    * Parses the response of a folder request and returns a corresponding
    * result object. The parsing is delegated to the protocol. The protocol
    * also determines whether another request needs to be sent.
    *
    * @param controller the resolve controller
    * @param response   the response
    * @return a future with the result of the parse operation
    */
  private def parseFolderResponse(controller: UriResolverController, response: HttpResponse):
  Future[ParseFolderResult] =
    controller.extractNamesFromFolderResponse(response)

  /**
    * Generates a map that associates decrypted element names with the original
    * names. The keys of the map are the decrypted names, the values the
    * original names. The map is produced asynchronously.
    *
    * @param names the list of names to be decrypted
    * @return a future with the map with decrypted and original names
    */
  private def decryptElementNames(names: Seq[String]): Future[Map[String, String]] = {
    val futDecryptedNames = names map decryptName
    Future.sequence(futDecryptedNames) map { decryptedNames =>
      decryptedNames.zip(names).toMap
    }
  }

  /**
    * Decrypts a file name in background.
    *
    * @param name the name to be decrypted
    * @return a ''Future'' with the decrypted name
    */
  private def decryptName(name: String): Future[String] = Future {
    CryptService.decryptName(decryptKey, UriHelper.urlDecode(name))
  }
}

/**
  * Class representing the ''Depth'' header.
  *
  * This header has to be included to WebDav requests. It defines the depth of
  * sub structures to be returned by a ''PROPFIND'' request.
  *
  * @param depth the value of the header
  */
class DepthHeader(depth: String) extends ModeledCustomHeader[DepthHeader] {
  override val companion: ModeledCustomHeaderCompanion[DepthHeader] = DepthHeader

  override def value(): String = depth

  override def renderInRequests(): Boolean = true

  override def renderInResponses(): Boolean = true
}

object DepthHeader extends ModeledCustomHeaderCompanion[DepthHeader] {
  override val name: String = "Depth"

  override def parse(value: String): Try[DepthHeader] =
    Try(new DepthHeader(value))
}
