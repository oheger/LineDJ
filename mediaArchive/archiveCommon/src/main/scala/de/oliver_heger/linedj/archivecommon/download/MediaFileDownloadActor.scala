/*
 * Copyright 2015-2017 The Developers Team.
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
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor._
import de.oliver_heger.linedj.shared.archive.media.{DownloadComplete, DownloadData, DownloadDataResult}

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

}

/**
  * An actor handling the download of a specific media file from the media
  * archive.
  *
  * This actor class manages a ''Source'' to a file located on the local hard
  * disk. If requested, ID3 tags can be filtered out automatically. The data
  * of this source is then made available to clients via a request-response
  * protocol:
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
  * @param path           the path to the file to be downloaded
  * @param chunkSize      the chunk size for read operations
  * @param filterMetaData flag whether meta data should be filtered
  */
class MediaFileDownloadActor(path: Path, chunkSize: Int, filterMetaData: Boolean)
  extends Actor with ActorLogging {
  /** The object to materialize streams. */
  private implicit val materializer = ActorMaterializer()

  /** Stores a current block of data. */
  private var currentData: Option[ByteString] = None

  /** Stores information about a currently requesting client. */
  private var currentRequest: Option[DownloadRequest] = None

  /** The actor sending messages on behalf of the source. */
  private var sourceActor: ActorRef = _

  /** A flag whether download has already been completed. */
  private var complete = false

  override def preStart(): Unit = {
    val source = createSource()
    //TODO apply filtering
    val filterSource = /*if (filterMetaData) source.via(new ID3v2ProcessingStage(None))
    else */source
    val sink = Sink.actorRefWithAck(self, InitMsg, AckMsg, CompleteMsg, ex => ErrorMsg(ex))
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

    case DownloadData(_) if currentRequest.isDefined =>
      sender ! DownloadDataResult(ByteString.empty)
  }

  /**
    * Creates the source for the stream. This is a source which reads the
    * specified path.
    *
    * @return the source for the stream
    */
  private[download] def createSource(): Source[ByteString, Any] =
    FileIO.fromPath(path, chunkSize)

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