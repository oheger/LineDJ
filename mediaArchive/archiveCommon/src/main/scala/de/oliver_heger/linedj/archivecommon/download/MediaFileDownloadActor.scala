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

package de.oliver_heger.linedj.archivecommon.download

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.stream.scaladsl.{FileIO, Flow, Sink, Source}
import akka.stream.{FlowShape, Graph}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor._
import de.oliver_heger.linedj.io.PathUtils
import de.oliver_heger.linedj.shared.archive.media._

object MediaFileDownloadActor {

  /** Internally used init message. */
  private case object InitMsg

  /** Internally used ACK message. */
  private case object AckMsg

  /** Internally used complete message. */
  private case object CompleteMsg

  /** Internally used error message. */
  private case class ErrorMsg(exception: Throwable)

  /**
    * Internally used data class to store a request of a download chunk from a
    * client actor.
    *
    * @param client  the client actor
    * @param request the request
    */
  private case class DownloadRequest(client: ActorRef, request: DownloadData)

  /**
    * Definition of a transformation function for the streams to be downloaded
    * to clients. It is sometimes necessary to somehow manipulate the content
    * of a media file that is sent to a client. One example is filtering out
    * of meta data (like ID3 tags).
    *
    * This can be achieved by passing a concrete transformation function to
    * the actor. The function is invoked with the file extension of the file to
    * be downloaded. It can then return a flow stage that performs a
    * transformation on the stream. The idea is that different file types may
    * require different transformations. If a file extension is not handled by
    * the function, no transformation is applied.
    */
  type DownloadTransformFunc =
    PartialFunction[String, Graph[FlowShape[ByteString, ByteString], Any]]

  /**
    * An implementation of a transformation function which does not apply any
    * transformation. This function does not touch the underlying file source,
    * no matter which file extension is passed in. Actually, it is a partial
    * function that is defined nowhere.
    */
  object IdentityTransform extends DownloadTransformFunc {
    override def isDefinedAt(x: String): Boolean = false

    override def apply(ext: String): Flow[ByteString, ByteString, Any] =
      throw new UnsupportedOperationException("apply() not supported for IdentityTransform!")
  }
}

/**
  * An actor handling the download of a specific media file from the media
  * archive.
  *
  * This actor class manages a ''Source'' to a file located on the local hard
  * disk. By specifying a transformation function, the content of the file to
  * be downloaded can be manipulated; this is mainly used to remove meta data
  * from a media file.
  *
  * The data of this source is then made available to clients via a
  * request-response protocol:
  *
  *  - By sending a ''DownloadData'' message, a client requests a block of
  * data with the specified size.
  *  - Such messages are answered with ''DownloadDataResult'' messages with
  * the data obtained from the file.
  *  - If all data has been sent, a ''DownloadComplete'' message is sent.
  *  - Only a single request can be processed at a time. If another request is
  * sent while one is pending, a ''DownloadDataResult'' with an empty byte
  * string is sent immediately.
  *
  * @param path            the path to the file to be downloaded
  * @param chunkSize       the chunk size for read operations
  * @param trans           a function to optionally transform the data source
  * @param optManagerActor an optional reference to the download manager actor;
  *                        this actor is notified if there is activity
  */
class MediaFileDownloadActor(path: Path, chunkSize: Int, trans: DownloadTransformFunc,
                             optManagerActor: Option[ActorRef])
  extends Actor with ActorLogging {

  def this(path: Path, chunkSize: Int, trans: DownloadTransformFunc) =
    this(path, chunkSize, trans, None)

  /** A message to notify the download manager when there is activity. */
  private var aliveMessage: Any = _

  /** Stores a current block of data. */
  private var currentData: Option[ByteString] = None

  /** Stores information about a currently requesting client. */
  private var currentRequest: Option[DownloadRequest] = None

  /** The actor sending messages on behalf of the source. */
  private var sourceActor: ActorRef = _

  /** A flag whether download has already been completed. */
  private var complete = false

  override def preStart(): Unit = {
    import context.system
    aliveMessage = DownloadActorAlive(self, MediaFileID(MediumID.UndefinedMediumID, ""))
    val source = createSource()
    val filterSource = applyTransformation(source)
    val sink = Sink.actorRefWithBackpressure(self, InitMsg, AckMsg, CompleteMsg, ex => ErrorMsg(ex))
    filterSource.runWith(sink)
  }

  override def receive: Receive = {
    case InitMsg =>
      sender ! AckMsg
      log.info("Starting download of {}.", path)

    case bs: ByteString =>
      currentData = Some(bs)
      sourceActor = sender()
      sendDownloadResponseIfAvailable()

    case CompleteMsg =>
      complete = true
      sendDownloadResponseIfAvailable()

    case ErrorMsg(exception) =>
      log.error(exception, "Error when downloading file " + path)
      context stop self

    case req: DownloadData if currentRequest.isEmpty =>
      currentRequest = Some(DownloadRequest(sender(), req))
      sendDownloadResponseIfAvailable()
      optManagerActor foreach(_ ! aliveMessage)

    case DownloadData(_) if currentRequest.isDefined =>
      sender ! DownloadDataResult(ByteString.empty)
  }

  /**
    * Creates the source for the stream. This is a source which reads the
    * specified path.
    *
    * @return the source for the stream
    */
  protected def createSource(): Source[ByteString, Any] =
    FileIO.fromPath(path, chunkSize)

  /**
    * Applies the transformation function to the specified source. If a
    * transformation is defined for the current file extension, it is added to
    * the source. Otherwise, the source is returned without changes.
    *
    * @param source the source to be transformed
    * @return the transformed source
    */
  private def applyTransformation(source: Source[ByteString, Any]): Source[ByteString, Any] = {
    val extension = PathUtils extractExtension path.toString
    if (trans isDefinedAt extension) source.via(trans(extension))
    else source
  }

  /**
    * Checks whether a download response can be sent to a client actor. This
    * is possible if a client request if available and data has been received
    * from the source.
    */
  private def sendDownloadResponseIfAvailable(): Unit = {
    currentRequest foreach { req =>
      val (nextData, optMsg) = downloadResponse(req)
      currentData = nextData
      optMsg match {
        case Some(msg) =>
          req.client ! msg
          currentRequest = None
          if (nextData.isEmpty) {
            sourceActor ! AckMsg
          }
        case None =>
          if (complete) {
            req.client ! DownloadComplete
            currentRequest = None
          }
      }
    }
  }

  private def downloadResponse(request: DownloadRequest):
  (Option[ByteString], Option[DownloadDataResult]) = {
    currentData match {
      case Some(bs) =>
        currentDownloadChunk(request, bs)
      case None => (None, None)
    }
  }

  /**
    * Returns a tuple regarding a chunk of data to be downloaded. The currently
    * available data may be too large to be returned directly to the sender.
    * In this case, it has to be split. This function returns a tuple with the
    * remaining data (''None'' if all data could be sent) and the message to be
    * sent to the client.
    *
    * @param request the request data object
    * @param data    the current data
    * @return the remaining data and the message to be sent
    */
  private def currentDownloadChunk(request: DownloadRequest, data: ByteString):
  (Option[ByteString], Option[DownloadDataResult]) =
    if (data.length <= request.request.size) (None, Some(DownloadDataResult(data)))
    else {
      val (msg, remaining) = data splitAt request.request.size
      (Some(remaining), Some(DownloadDataResult(msg)))
    }
}
