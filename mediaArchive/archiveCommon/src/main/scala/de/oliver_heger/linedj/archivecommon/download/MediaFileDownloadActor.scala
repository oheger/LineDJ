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

import akka.actor.{ActorLogging, ActorRef}
import akka.stream.scaladsl.{FileIO, Flow, Source}
import akka.stream.{FlowShape, Graph}
import akka.util.ByteString
import de.oliver_heger.linedj.archivecommon.download.MediaFileDownloadActor._
import de.oliver_heger.linedj.io.PathUtils
import de.oliver_heger.linedj.io.stream.StreamPullModeratorActor
import de.oliver_heger.linedj.shared.archive.media._

object MediaFileDownloadActor {

  /** Internally used error message. */
  private case class ErrorMsg(exception: Throwable)

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
  extends StreamPullModeratorActor with ActorLogging {

  def this(path: Path, chunkSize: Int, trans: DownloadTransformFunc) =
    this(path, chunkSize, trans, None)

  /** A message to notify the download manager when there is activity. */
  private var aliveMessage: Any = _

  override def preStart(): Unit = {
    log.info("Starting download of {}.", path)
    super.preStart()
    aliveMessage = DownloadActorAlive(self, MediaFileID(MediumID.UndefinedMediumID, ""))
  }

  /**
    * Creates the source for the stream. This is a source which reads the
    * specified path and applies a transformation on the data if necessary.
    *
    * @return the source for the stream
    */
  protected def createSource(): Source[ByteString, Any] =
    applyTransformation(FileIO.fromPath(path, chunkSize))

  override protected def customReceive: Receive = {
    case DownloadData(size) =>
      dataRequested(size)
      optManagerActor foreach (_ ! aliveMessage)

    case ErrorMsg(exception) =>
      log.error(exception, "Error when downloading file " + path)
      context stop self
  }

  override protected def convertStreamError(exception: Throwable): Any = ErrorMsg(exception)

  override protected def dataMessage(data: ByteString): Any = DownloadDataResult(data)

  override protected val endOfStreamMessage: Any = DownloadComplete

  override protected val concurrentRequestMessage: Any = DownloadDataResult(ByteString.empty)

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
}
