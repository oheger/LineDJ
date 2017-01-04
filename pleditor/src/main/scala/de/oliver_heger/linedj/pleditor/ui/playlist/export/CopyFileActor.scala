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

package de.oliver_heger.linedj.pleditor.ui.playlist.export

import java.io.IOException
import java.nio.file.Path

import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import de.oliver_heger.linedj.io._
import de.oliver_heger.linedj.shared.archive.media.{MediumFileRequest, MediumFileResponse}

object CopyFileActor {
  /**
   * The default size for progress notification messages.
   */
  val DefaultProgressNotificationSize = 1024 * 1024

  /** The buffer size for copying file data. */
  private val CopyBufferSize = 16384

  /**
   * A message processed by [[CopyFileActor]] telling it to download a file and
   * copy it to the specified target path.
   *
   * Such a message is generated for each file in the playlist to be exported.
   * When the copy operation is complete, a corresponding ''MediumFileCopied''
   * message is sent to the owning actor.
   *
   * @param request the request for the file to be downloaded
   * @param target the target path
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
   * @param target the target path
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
   * @param size the number of bytes copied for this file
   */
  case class CopyProgress(copyRequest: CopyMediumFile, size: Int)

  /**
   * Creates a ''Props'' object for creating an instance of this actor class.
   * @param parent the parent actor (the one that receives responses)
   * @param mediaManager the media manager actor
   * @return a ''Props'' for creating an actor instance
   */
  def apply(parent: ActorRef, mediaManager: ActorRef): Props =
    Props(classOf[CopyFileActor], parent, mediaManager)

  /**
   * An internally used data class which stores information about a currently
   * running copy operation.
   *
   * @param copyState the copy state
   * @param readerActor the reader actor to be used
   */
  private case class CopyOperationData(copyState: CopyState, readerActor: ActorRef)

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
 * @param parent the parent actor (the one that receives responses)
 * @param mediaManager the media manager actor
 * @param progressNotificationSize the number of bytes after which a progress
 *                                 notification message is sent
 */
class CopyFileActor(parent: ActorRef, mediaManager: ActorRef, val progressNotificationSize: Int)
  extends Actor {

  import CopyFileActor._

  /** The actor for writing files to their target location. */
  private var writerActor: ActorRef = _

  /** The currently processed copy request. */
  private var currentCopyRequest: CopyMediumFile = _

  /** Stores data about the current copy request. */
  private var copyOperationData: Option[CopyOperationData] = None

  /** A counter for the number of bytes that have been written. */
  private var bytesWritten = 0

  /** A counter for the progress of a copy operation. */
  private var copyProgressCount = 0

  /**
   * Creates a new instance of ''CopyFileActor'' with a default progress
   * notification size.
   * @param parent the parent actor
   * @param mediaManager the media manager actor
   * @return the new actor instance
   */
  def this(parent: ActorRef, mediaManager: ActorRef) = this(parent, mediaManager,
    CopyFileActor.DefaultProgressNotificationSize)

  /**
   * This strategy delegates all exceptions to the parent actor. If a child
   * actor fails, the whole copy actor should be considered in a problematic
   * state.
   */
  override val supervisorStrategy = OneForOneStrategy() {
    case _: Exception => Escalate
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    writerActor = context.actorOf(Props[FileWriterActor], "exportFileWriter")
  }

  override def receive: Receive = {
    case copyRequest: CopyMediumFile if currentCopyRequest == null =>
      mediaManager ! copyRequest.request
      currentCopyRequest = copyRequest

    case MediumFileResponse(request, reader, _) if copyOperationData.isEmpty &&
      currentCopyRequest != null =>
      context watch reader
      val transition = CopyState.init(CopyBufferSize)
      val state = transition.nextState
      reader ! state.readRequest
      writerActor ! ChannelHandler.InitFile(currentCopyRequest.target)
      copyOperationData = Some(CopyOperationData(state, reader))

    case CancelCopyOperation =>
      copyOperationData foreach copyRequestCompleted

    case readResult: FileReaderActor.ReadResult =>
      handleCopyMessage(readResult)

    case writeResult: FileWriterActor.WriteResult =>
      handleCopyMessage(writeResult)

    case eof: FileReaderActor.EndOfFile =>
      handleCopyMessage(eof)

    case close: CloseAck =>
      handleCopyMessage(close)

    case _: Terminated =>
      // the current reader actor died; throw an exception to delegate to the
      // parent actor
      throw new IOException("Reader actor died!")
  }

  /**
   * Processes a message which is related to the current copy operation.
   * @param msg the message to be handled
   */
  private def handleCopyMessage(msg: Any): Unit = {
    copyOperationData = copyOperationData flatMap { data =>
      checkProgress(msg)
      val transition = data.copyState update msg
      transition.sendMessages(data.readerActor, writerActor)

      if (transition.nextState.done) {
        copyRequestCompleted(data)
        None
      } else Some(data.copy(copyState = transition.nextState))
    }
  }

  /**
   * This method is called when a copy request is complete. It cleans up some
   * internal fields and sends out corresponding messages. Note that it is not
   * necessary to close the writer actor: If the copy operation was successful,
   * it has already been closed. Otherwise, it will be closed automatically
   * either when copying the next file or when this actor (and all its
   * children) dies.
   * @param data the data about the completed operation
   */
  private def copyRequestCompleted(data: CopyOperationData): Unit = {
    parent ! MediumFileCopied(currentCopyRequest.request, currentCopyRequest.target)
    context unwatch data.readerActor
    context stop data.readerActor
    currentCopyRequest = null
    bytesWritten = 0
    copyProgressCount = 0
  }

  /**
   * Checks whether a message about the progress of a copy operation has to be
   * sent out.
   * @param msg the currently received message
   */
  private def checkProgress(msg: Any): Unit = {
    msg match {
      case writeResult: FileWriterActor.WriteResult =>
        bytesWritten += writeResult.bytesWritten
        val progressCount = bytesWritten / progressNotificationSize
        if (copyProgressCount != progressCount) {
          copyProgressCount = progressCount
          parent ! CopyProgress(currentCopyRequest, progressCount * progressNotificationSize)
        }

      case _ =>
    }
  }
}
