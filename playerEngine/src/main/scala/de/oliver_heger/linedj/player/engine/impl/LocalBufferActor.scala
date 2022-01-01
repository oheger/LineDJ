/*
 * Copyright 2015-2022 The Developers Team.
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

package de.oliver_heger.linedj.player.engine.impl

import java.nio.file.Path

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status, Terminated}
import akka.stream.CompletionStrategy
import akka.stream.scaladsl.{FileIO, Keep, Source}
import akka.util.ByteString
import de.oliver_heger.linedj.io.stream.StreamPullModeratorActor
import de.oliver_heger.linedj.io.{CloseAck, CloseRequest}
import de.oliver_heger.linedj.player.engine.PlayerConfig
import de.oliver_heger.linedj.player.engine.impl.BufferFileManager.BufferFile
import de.oliver_heger.linedj.player.engine.impl.LocalBufferActor._
import de.oliver_heger.linedj.shared.archive.media.{DownloadComplete, DownloadData, DownloadDataResult}
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.util.{Failure, Success, Try}

/**
  * Companion object to ''LocalBufferActor''.
  */
object LocalBufferActor {

  /**
    * A message that tells the ''LocalBufferActor'' to fill its buffer with the
    * content read from the contained ''FileReaderActor''.
    *
    * In reaction of this message, the buffer actor reads the content of the
    * file pointed to by the passed in actor and stores it in a temporary
    * file. From there it can later be read again during audio playback.
    *
    * @param readerActor the reader actor to be read
    */
  case class FillBuffer(readerActor: ActorRef)

  /**
    * A message sent by the buffer actor after the content of a reader actor
    * has been processed and written into the buffer.
    *
    * @param readerActor  the reader actor that has been read
    * @param sourceLength the length of the source filled into the buffer
    */
  case class BufferFilled(readerActor: ActorRef, sourceLength: Long)

  /**
    * A message requesting a file to be read from the buffer.
    *
    * As soon as data is available (i.e. after a complete temporary file has
    * been created in the buffer), a [[BufferReadActor]] message is sent to
    * the caller. Note that only a single read request can be handled at a
    * point in time; sending another request while the buffer is still read
    * causes a [[BufferBusy]] message.
    */
  case object ReadBuffer

  /**
    * A message sent in response on a [[ReadBuffer]] request.
    *
    * As soon as a temporary file is ready for being read, this message is sent
    * to the original caller. It contains a reference to an actor from which
    * chunks of data can be requested. This is done by sending
    * [[BufferDataRequest]] messages to this actor; it replies with
    * [[BufferDataResult]] and eventually [[BufferDataComplete]] messages.
    * After the data has been fully read, the actor must be stopped. This
    * causes the underlying file to be marked as read and removed from the
    * buffer.
    *
    * In addition to the reader actor, this message contains a list of length
    * values for the sources that are fully contained in the underlying file.
    * This allows clients to determine when the data of a source ends and the
    * next one begins.
    *
    * @param readerActor   the actor for reading data from the buffer
    * @param sourceLengths length values for the sources in the file
    */
  case class BufferReadActor(readerActor: ActorRef, sourceLengths: List[Long])

  /**
    * A message to request a new chunk of data from an actor obtained via a
    * [[BufferReadActor]] message.
    *
    * By sending this message repeatedly to the reader actor, the whole
    * content of the current temporary buffer file can be read.
    *
    * @param chunkSize the size of the desired data
    */
  case class BufferDataRequest(chunkSize: Int)

  /**
    * A message sent in reaction to a [[BufferDataRequest]] message with a
    * chunk of data.
    *
    * @param data the data of this chunk
    */
  case class BufferDataResult(data: ByteString)

  /**
    * A message sent in reaction to a [[BufferDataRequest]] message if no more
    * data is available in the current temporary file.
    *
    * When receiving this message, the client knows that the current file has
    * been read completely. The reader actor should then be stopped.
    */
  case object BufferDataComplete

  /**
    * A message processed by [[LocalBufferActor]] that notifies the buffer
    * that a buffer read operation is complete.
    *
    * This message is sent by a client that has received a [[BufferReadActor]]
    * message when the reader actor has been fully processed.
    *
    * @param readerActor the reader actor that has been read
    */
  case class BufferReadComplete(readerActor: ActorRef)

  /**
    * A message that is send by the buffer to indicate a request which cannot be
    * handled in the current state.
    *
    * ''LocalBufferActor'' only supports a single fill and a single read
    * operation at the same time. If clients try to start another
    * simultaneous operation, this message is sent as answer indicating a
    * violation of the buffer protocol.
    */
  case object BufferBusy

  /**
    * A message telling the buffer that the current sequence of files to be
    * buffered is complete.
    *
    * Typically, the buffer closes a temporary file not before the configured
    * size is reached. When the end of the playlist is reached, there is
    * typically space left in the buffer. This messages causes the buffer to
    * close an open temporary file and make it available for reading.
    */
  case object SequenceComplete

  /**
    * An internal message indicating that a write operation to a temporary
    * buffer file has been completed.
    */
  private case object WriteStreamComplete

  /**
    * An internal message used to acknowledge a single write operation into the
    * current write stream.
    */
  private case object WriteStreamAck

  /**
    * An internal data class holding information about the current write
    * operation into a temporary file.
    *
    * @param sourceActor the actor used as source for the write stream
    * @param currentPath the current path to the temporary file
    */
  private case class WriteStreamData(sourceActor: ActorRef, currentPath: Path) {
    /**
      * Notifies the source actor for the write stream that all data has been
      * written and the stream can be closed now.
      */
    def closeStream(): Unit = {
      sourceActor ! WriteStreamComplete
    }
  }

  /**
    * An actor class that provides access to the data of a file managed by
    * ''LocalBufferActor''.
    *
    * The file is read via a stream. Its content is exposed to a client which
    * sends [[BufferDataRequest]] messages to this actor is is served with
    * [[BufferDataResult]] messages.
    *
    * @param path      the path to the file to be read
    * @param chunkSize size of read chunks
    */
  private class BufferFileReadActor(path: Path, chunkSize: Int) extends StreamPullModeratorActor with ActorLogging {
    override protected def createSource(): Source[ByteString, Any] =
      Try(FileIO.fromPath(path, chunkSize = chunkSize)) match {
        case Success(source) => source
        case Failure(exception) => Source.failed(exception)
      }

    override protected def customReceive: Receive = {
      case BufferDataRequest(size) =>
        dataRequested(size)

      case Status.Failure(exception) =>
        log.error(exception, "Error when reading buffer file {}.", path)
        context stop self
    }

    override protected def dataMessage(data: ByteString): Any = BufferDataResult(data)

    override protected val endOfStreamMessage: Any = BufferDataComplete

    override protected val concurrentRequestMessage: Any = BufferDataResult(ByteString.empty)
  }

  private class LocalBufferActorImpl(config: PlayerConfig, bufferManager: BufferFileManager)
    extends LocalBufferActor(config, bufferManager) with ChildActorFactory

  /**
    * Creates a ''Props'' object which can be used to create new actor instances
    * of this class. This method should always be used; it guarantees that all
    * required dependencies are satisfied.
    *
    * @param config        an object with configuration settings
    * @param bufferManager the object for managing temporary files
    * @return a ''Props'' object for creating actor instances
    */
  def apply(config: PlayerConfig, bufferManager: BufferFileManager): Props =
    Props(classOf[LocalBufferActorImpl], config, bufferManager)
}

/**
  * An actor for managing a local buffer for streamed audio data.
  *
  * The audio player engine reads data from the source medium and stores it in a
  * buffer on the local hard disk before it is actually played. This actor is
  * responsible for the management of this buffer.
  *
  * Basically, the buffer consists of two temporary files of a configurable
  * size. The buffer actor supports a message for filling the buffer which
  * references a ''MediaFileDownloadActor''. This actor is used to read data
  * until the buffer is full. On the other hand, a ''FileReaderActor'' can be
  * queried for reading from the buffer.
  *
  * Filling the buffer works by reading the content of the provided actor
  * and storing it in a temporary file. When the first temporary file is
  * completely written (i.e. its configured file size is reached) it is made
  * available for reading. At the same time the second temporary file can be
  * filled. When the first file is completely written it is removed. Reading
  * continues with the other file. At this point in time, another temporary file
  * is created which can be filled again. With other words: when the half of the
  * buffer has been read it can be filled again.
  *
  * At a time only a single read and a single fill operation are allowed. If a
  * request for another operation arrives, a busy message is returned.
  *
  * @param config        an object with configuration settings
  * @param bufferManager the object for managing temporary files
  */
class LocalBufferActor(config: PlayerConfig, bufferManager: BufferFileManager)
  extends Actor with ActorLogging {
  this: ChildActorFactory =>

  /** The object for handling a close operation. */
  private var closingState: ClosingState = _

  /** The current reader actor for filling the buffer. */
  private var fillActor: Option[ActorRef] = None

  /** The client responsible for the current fill operation. */
  private var fillClient: ActorRef = _

  /** The current actor for reading data from the buffer. */
  private var readActor: ActorRef = _

  /** A client requesting a buffer read operation. */
  private var readClient: Option[ActorRef] = None

  /**
    * Stores information about the current write operation to a temporary file.
    */
  private var currentWrite: Option[WriteStreamData] = None

  /** A read result which has to be processed after finishing the current write operation. */
  private var pendingReadResult: Option[ByteString] = None

  /**
    * Stores a message about a completed fill operation. The message may be
    * stored to be sent out later when there is again space in the buffer to
    * continue filling.
    */
  private var fillCompleteMessage: Option[BufferFilled] = None

  /**
    * Stores the lengths of sources that have been written completely into the
    * current temporary file.
    */
  private var sourceLengths = List.empty[Long]

  /** The number of bytes that have been written to the current temporary file. */
  private var bytesWrittenToFile = 0

  /** The number of bytes written for the current source. */
  private var bytesWrittenForSource = 0L

  /** A flag whether a fill request is pending. */
  private var pendingFillRequest = false

  /** A flag whether currently a read operation is in progress. */
  private var readOperationInProgress = false

  /**
    * @inheritdoc This implementation creates a write actor as a child.
    *             This actor is re-used for creating files in the buffer.
    */
  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    bufferManager.clearBufferDirectory()
  }

  override def receive: Receive = {
    case FillBuffer(actor) =>
      if (fillActor.isDefined) {
        sender() ! BufferBusy
      } else {
        bytesWrittenForSource = 0
        fillActor = Some(actor)
        fillClient = sender()
        pendingFillRequest = true
        serveFillRequest()
        context watch actor
      }

    case DownloadDataResult(readResult) =>
      readOperationInProgress = false
      handleReadResult(readResult)

    case WriteStreamAck =>
      if (bytesWrittenToFile >= config.bufferFileSize) {
        closeWriteStream()
      } else {
        continueFilling()
      }

    case WriteStreamComplete =>
      currentWrite foreach { write =>
        bufferManager append BufferFile(write.currentPath, sourceLengths.reverse)
      }
      currentWrite = None
      sourceLengths = Nil
      bytesWrittenToFile = 0
      serveReadRequest()
      continueFilling()

    case DownloadComplete =>
      fillOperationCompleted(sender())

    case ReadBuffer =>
      if (readClient.isDefined) {
        sender() ! BufferBusy
      } else {
        readClient = Some(sender())
        serveReadRequest()
      }

    case BufferReadComplete(actor) if actor == readActor =>
      handleCompletedReadOperation()

    case Terminated(actor) if actor == readActor =>
      handleCompletedReadOperation()

    case Terminated(actor) if actor != readActor =>
      fillOperationCompleted(actor)

    case SequenceComplete =>
      closeWriteStream()

    case CloseRequest =>
      closingState = new ClosingState(sender())
      closingState.initiateClosing()
      context become closing
  }

  def closing: Receive = {
    case FillBuffer(_) =>
      sender() ! BufferBusy

    case WriteStreamComplete =>
      closingState.writeActorClosed()

    case ReadBuffer =>
      sender() ! BufferBusy

    case SequenceComplete =>
      sender() ! BufferBusy

    case BufferReadComplete(actor) if actor == readActor =>
      handleCompletedReadOperationOnClosing()

    case Terminated(actor) if actor == readActor =>
      handleCompletedReadOperationOnClosing()
  }

  /**
    * Continues a fill operation. This method is called after data obtained from
    * the current fill actor was written by the writer actor. It either
    * requests the next chunk of data or processes remaining bytes to be
    * written.
    */
  private def continueFilling(): Unit = {
    if (!bufferManager.isFull) {
      pendingReadResult match {
        case Some(result) =>
          handleReadResult(result)
        case None =>
          continueDownload()
      }
    }
  }

  /**
    * Continues a download operation. This method is called after all pending
    * data has been written or the current read actor has been finished.
    */
  private def continueDownload(): Unit = {
    if (!readOperationInProgress) {
      fillCompleteMessage match {
        case Some(msg) =>
          fillClient ! msg
          fillCompleteMessage = None

        case None =>
          fillActor foreach { a =>
            a ! DownloadData(config.bufferChunkSize)
            readOperationInProgress = true
          }
      }
    }
  }

  /**
    * A fill operation has been completed. The corresponding message is sent if
    * actually the fill actor is affected.
    *
    * @param actor the actor responsible for this message
    */
  private def fillOperationCompleted(actor: ActorRef): Unit = {
    fillActor foreach { a =>
      if (actor == a) {
        val sourceLen = bytesWrittenForSource + pendingReadResult.map(_.length).getOrElse(0)
        val fillMsg = BufferFilled(a, sourceLen)
        if (bufferManager.isFull) {
          fillCompleteMessage = Some(fillMsg)
        } else {
          fillClient ! fillMsg
        }
        fillActor = None
        pendingReadResult = None
        context unwatch a
        sourceLengths = sourceLen :: sourceLengths
        log.info("Source completed. Read {} bytes.", sourceLen)
      }
    }
  }

  /**
    * Closes the current write stream if one is active.
    */
  private def closeWriteStream(): Unit = {
    currentWrite foreach (_.closeStream())
  }

  /**
    * Handles a completed read operation. A read operation can either be
    * completed explicitly by sending a ''BufferReadComplete'' message.
    * Alternatively, the operation is completed when the reader actor is
    * stopped.
    */
  private def handleCompletedReadOperation(): Unit = {
    val bufferBlocked = bufferManager.isFull
    stopReaderActor()
    if (bufferBlocked) {
      continueFilling()
    }
    readActor = null
  }

  /**
    * Handles a completed read operation when the actor is in closing state.
    */
  private def handleCompletedReadOperationOnClosing(): Unit = {
    stopReaderActor()
    closingState.readActorStopped()
  }

  /**
    * Stops the current reader actor and completes the read operation.
    */
  private def stopReaderActor(): Unit = {
    context unwatch readActor
    context stop readActor
    completeReadOperation()
  }

  /**
    * A read operation has been completed. The temporary file can now be
    * removed from the buffer.
    */
  private def completeReadOperation(): Unit = {
    val removedPath = bufferManager.checkOutAndRemove()
    log.info("Finished temporary file {}.", removedPath)
    readClient = None
  }

  /**
    * Handles the result of a read operation. The normal processing is that the
    * data that was read is now written into the current output file. (If none
    * is open, a new one is created now.) The maximum size of the output file is
    * ensured; if necessary, the data is split, and only a part is written now.
    * The remaining data is processed after the write request completed. If the
    * buffer is already full, this method does nothing; further action is not
    * allowed before data has been read from the buffer.
    *
    * @param readResult the object with the result of the read operation
    */
  private def handleReadResult(readResult: ByteString): Unit = {
    val (request, pending) = currentAndPendingWriteRequest(readResult)
    ensureWriteActorInitialized() ! request
    pendingReadResult = pending
    bytesWrittenToFile += request.length
    bytesWrittenForSource += request.length
  }

  /**
    * Initializes the write actor for a new temporary file if this is necessary.
    * This method creates a new temporary file name and passes it to the write
    * actor. This action is needed whenever the maximum size of a temporary file
    * was reached and it has been closed.
    *
    * @return a reference to the initialized write actor
    */
  private def ensureWriteActorInitialized(): ActorRef =
    currentWrite match {
      case Some(writeData) =>
        writeData.sourceActor
      case None =>
        val path = bufferManager.createPath()
        log.info("Creating new temporary file {}.", path)
        val writeActor = createWriteActor(path)
        currentWrite = Some(WriteStreamData(writeActor, path))
        writeActor
    }

  /**
    * Creates an actor to be used as a source for writing into a temporary
    * file. A stream is created to write the file, and sending messages to the
    * actor causes the data to be written to the file.
    *
    * @param path the path of the temporary buffer file
    * @return the actor to write data into the file
    */
  private def createWriteActor(path: Path): ActorRef = {
    val source = Source.actorRefWithBackpressure[ByteString](WriteStreamAck, {
      case WriteStreamComplete => CompletionStrategy.immediately
    }, PartialFunction.empty)
    val sink = FileIO.toPath(path)
    import context.{dispatcher, system}
    val (sourceActor, futResult) = source.toMat(sink)(Keep.both).run()
    futResult onComplete { triedResult =>
      triedResult match {
        case Failure(exception) =>
          log.error(exception, "Write error when writing to buffer file!")
        case _ =>
      }
      self ! WriteStreamComplete
    }
    sourceActor
  }

  /**
    * Determines the current and the pending write request based on the passed
    * in read result. This method checks whether the data of the passed in
    * result object still fits into the current temporary file. If this is not
    * the case, the request has to be split into two.
    *
    * @param readResult the read result
    * @return a tuple with the current and the pending write request
    */
  private def currentAndPendingWriteRequest(readResult: ByteString): (ByteString, Option[ByteString]) = {
    if (readResult.length + bytesWrittenToFile <= config.bufferFileSize)
      (readResult, None)
    else {
      val actLength = config.bufferFileSize - bytesWrittenToFile
      val splitData = readResult splitAt actLength
      (splitData._1, Some(splitData._2))
    }
  }

  /**
    * Checks whether a fill request is currently pending and can be served.
    * This method checks whether all criteria are fulfilled to start a new
    * fill operation. It is called when conditions change that might affect
    * whether a fill operation is possible or not.
    */
  private def serveFillRequest(): Unit = {
    if (pendingFillRequest) {
      fillActor.get ! DownloadData(config.bufferChunkSize)
      pendingFillRequest = false
    }
  }

  /**
    * Checks whether a read request is currently pending and can be served. This
    * method is called when a read request comes in or new data is written into
    * the buffer. It checks whether all conditions are fulfilled to start a new
    * read operation. If this is the case, the request is handled.
    */
  private def serveReadRequest(): Unit = {
    if (readActor == null) {
      for {client <- readClient
           file <- bufferManager.read} {
        readActor = createChildActor(Props(new BufferFileReadActor(file.path, config.bufferChunkSize)))
        context watch readActor
        client ! BufferReadActor(readActor, file.sourceLengths)
      }
    }
  }

  /**
    * A class keeping track on information required for gracefully closing this
    * actor.
    *
    * Closing this actor is not trivial because there may be ongoing operations
    * that have to be canceled before cleanup can be done (e.g. removing of
    * currently open temporary files). This class collects the required
    * information and is also triggered when a change in the affected conditions
    * happens.
    *
    * @param closingActor the actor that triggered the closing operation
    */
  private class ClosingState(closingActor: ActorRef) {
    /** A flag whether the write actor is still open. */
    var writeActorPending = false

    /** A flag whether a read actor is currently open. */
    var readActorPending = false

    /**
      * Triggers a closing operation. The current state as it affects closing is
      * collected. If possible, the close is already executed.
      *
      * @return a flag whether this actor could be closed directly
      */
    def initiateClosing(): Boolean = {
      readActorPending = readClient.isDefined && readActor != null
      writeActorPending = currentWrite.isDefined
      closeWriteStream()
      closeIfPossible()
    }

    /**
      * Notifies this object that the write actor was closed. This may impact an
      * ongoing closing operation. If closing is in progress and now all
      * conditions are fulfilled, this actor is closed. The return value
      * indicates whether the caller can continue its current operation
      * ('''true''') or whether it should be aborted ('''false''').
      *
      * @return a flag whether the current operation can be continued
      */
    def writeActorClosed(): Boolean = {
      writeActorPending = false
      closeIfPossible()
    }

    /**
      * Notifies this object that the current read actor was stopped. This may impact an
      * ongoing closing operation. If closing is in progress and now all
      * conditions are fulfilled, this actor is closed. The return value
      * indicates whether the caller can continue its current operation
      * ('''true''') or whether it should be aborted ('''false''').
      *
      * @return a flag whether the current operation can be continued
      */
    def readActorStopped(): Boolean = {
      readActorPending = false
      closeIfPossible()
    }

    /**
      * Closes this actor if all conditions are fulfilled.
      *
      * @return a flag whether the current operation can be continued
      */
    private def closeIfPossible(): Boolean = {
      if (!writeActorPending && !readActorPending) {
        closingActor ! CloseAck(self)
        bufferManager.removeContainedPaths()
      }
      false
    }
  }

}
