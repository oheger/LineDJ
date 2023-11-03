/*
 * Copyright 2015-2023 The Developers Team.
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

package de.oliver_heger.linedj.pleditor.ui.playlist.plexport

import de.oliver_heger.linedj.io.stream.ActorSource.{ActorCompletionResult, ActorDataResult, ActorErrorResult}
import de.oliver_heger.linedj.io.stream._
import de.oliver_heger.linedj.shared.archive.media._
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.apache.pekko.NotUsed
import org.apache.pekko.actor._
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.nio.file.Path

object CopyFileActor {

  /**
    * A message processed by [[CopyFileActor]] telling it to download a file and
    * copy it to the specified target path.
    *
    * Such a message is generated for each file in the playlist to be exported.
    * When the copy operation is complete, a corresponding ''MediumFileCopied''
    * message is sent to the owning actor.
    *
    * @param request the request for the file to be downloaded
    * @param target  the target path
    */
  case class CopyMediumFile(request: MediumFileRequest, target: Path)

  /**
    * A message sent by [[CopyFileActor]] after a file has been copied
    * successfully.
    *
    * The properties of this message correspond to the ones of the request
    * message.
    *
    * @param request the request for the file to be downloaded
    * @param target  the target path
    */
  case class MediumFileCopied(request: MediumFileRequest, target: Path)

  /**
    * A message processed by [[CopyFileActor]] which causes a currently
    * processed copy operation to be canceled.
    *
    * This is useful for instance if the user is presented a cancel button. In
    * this case not only the total export but also the current copy operation
    * has to be terminated because it may take a longer time.
    */
  case object CancelCopyOperation

  /**
    * A message sent by [[CopyFileActor]] if a configurable number of bytes of
    * a medium file has been copied.
    *
    * Messages of this type can be used for instance to update a progress
    * indicator. As media files may become large, it is not sufficient to update
    * an indicator on a per-file basis. Instead, updates during a file copy
    * operation may be necessary.
    *
    * @param copyRequest the copy request which is currently processed
    * @param size        the number of bytes copied for this file
    */
  case class CopyProgress(copyRequest: CopyMediumFile, size: Int)

  /**
    * Internal message used to report a crash of the current download actor.
    */
  private case object DownloadActorDied

  /**
    * Internal class representing a stream processing error.
    *
    * @param ex the exception
    */
  private case class StreamFailure(ex: Throwable)

  private class CopyFileActorImpl(parent: ActorRef, mediaManager: ActorRef, chunkSize: Int,
                                  progressSize: Int)
    extends CopyFileActor(parent, mediaManager, chunkSize, progressSize)
      with ChildActorFactory with CancelableStreamSupport

  /**
    * Creates a ''Props'' object for creating an instance of this actor class.
    *
    * @param parent       the parent actor (the one that receives responses)
    * @param mediaManager the media manager actor
    * @param chunkSize    the chunk size for I/O operations
    * @param progressSize the number of bytes when to send a progress
    *                     notification
    * @return a ''Props'' for creating an actor instance
    */
  def apply(parent: ActorRef, mediaManager: ActorRef, chunkSize: Int,
            progressSize: Int): Props =
    Props(classOf[CopyFileActorImpl], parent, mediaManager, chunkSize, progressSize)

}

/**
  * An actor class that copies a file received from the server into a target
  * directory.
  *
  * This actor class is used during the export of a playlist to write the single
  * files to their target destination. For each file to be copied the actor is
  * sent a message. It then queries the ''MediaManagerActor'' for a reader
  * actor to download this file. The file content is read and written to the
  * target location. After this is done, a confirmation message is sent to the
  * owning actor.
  *
  * Errors during a copy operation cause exceptions which are propagated to the
  * supervisor.
  *
  * @param parent       the parent actor (the one that receives responses)
  * @param mediaManager the media manager actor
  * @param chunkSize    the chunk size for I/O operations
  * @param progressSize the number of bytes after which a progress
  *                     notification message is sent
  */
class CopyFileActor(parent: ActorRef, mediaManager: ActorRef, chunkSize: Int,
                    progressSize: Int) extends AbstractFileWriterActor with ActorLogging {
  this: ChildActorFactory with CancelableStreamSupport =>

  import CopyFileActor._

  /** The currently processed copy request. */
  private var currentCopyRequest: CopyMediumFile = _

  /** The current download actor. */
  private var downloadActor: ActorRef = _

  override def customReceive: Receive = {
    case copyRequest: CopyMediumFile if currentCopyRequest == null =>
      mediaManager ! copyRequest.request
      currentCopyRequest = copyRequest

    case MediumFileResponse(_, optReader, _) if downloadActor == null &&
      currentCopyRequest != null =>
      optReader match {
        case Some(reader) =>
          downloadActor = createChildActor(Props(classOf[StreamSourceActorWrapper],
            reader, DownloadActorDied))
          runCopyStream()
        case None =>
          sendCopyCompletedNotification()
      }

    case CancelCopyOperation =>
      cancelCurrentStreams()
  }

  /**
    * @inheritdoc This implementation reacts on the result of stream
    *             processing. In case of an error, the actor stops itself.
    *             Otherwise, a completion message is sent to the parent.
    */
  override protected def propagateResult(client: ActorRef, result: Any): Unit = {
    copyRequestCompleted()
    super.propagateResult(client, result)
  }

  /**
    * Creates the source for the copy stream.
    *
    * @return the source
    */
  private[plexport] def createSource(): Source[ByteString, NotUsed] =
    ActorSource[ByteString](downloadActor, DownloadData(chunkSize)) {
      case DownloadDataResult(data) => ActorDataResult(data)
      case DownloadComplete => ActorCompletionResult()
      case DownloadActorDied => ActorErrorResult(new Exception("Download actor died!"))
    }

  /**
    * Starts the stream for the current copy operation.
    */
  private def runCopyStream(): Unit = {
    val source = createSource()
      .via(new CopyProgressNotificationStage(parent, currentCopyRequest, progressSize))
    writeFile(source, currentCopyRequest.target,
      MediumFileCopied(currentCopyRequest.request, currentCopyRequest.target),
      parent)
  }

  /**
    * This method is called when a copy request is complete. It cleans up some
    * internal fields and sends out corresponding messages.
    */
  private def copyRequestCompleted(): Unit = {
    context stop downloadActor
    downloadActor = null
    currentCopyRequest = null
  }

  /**
    * Sends a notification that the current copy operation is complete.
    */
  private def sendCopyCompletedNotification(): Unit = {
    parent ! MediumFileCopied(currentCopyRequest.request, currentCopyRequest.target)
    currentCopyRequest = null
  }
}
