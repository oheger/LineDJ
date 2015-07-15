package de.oliver_heger.splaya.playback

import java.nio.file.{FileSystems, Path}

import akka.actor.{Actor, ActorRef, Props, Terminated}
import de.oliver_heger.splaya.io.ChannelHandler.{ArraySource, InitFile}
import de.oliver_heger.splaya.io.FileReaderActor.{EndOfFile, ReadData}
import de.oliver_heger.splaya.io.FileWriterActor.WriteResult
import de.oliver_heger.splaya.io.{CloseAck, CloseRequest, FileReaderActor, FileWriterActor}
import de.oliver_heger.splaya.utils.ChildActorFactory

/**
 * Companion object to ''LocalBufferActor''.
 */
object LocalBufferActor {
  /** The system property defining the temporary directory. */
  private val PropTempDir = "java.io.tmpdir"

  /** The prefix for all configuration properties related to this actor. */
  private val PropertyPrefix = "splaya.buffer."

  /** The property for the size of temporary files. */
  private val PropFileSize = PropertyPrefix + "fileSize"

  /** The property for the chunk size for I/O operations. */
  private val PropChunkSize = PropertyPrefix + "chunkSize"

  /** The property for the file prefix for temporary buffer files. */
  private val PropFilePrefix = PropertyPrefix + "filePrefix"

  /** The property for the file extension for temporary buffer files. */
  private val PropFileExtension = PropertyPrefix + "fileExtension"

  /**
   * Obtains the path for temporary files. This is the default value for the
   * buffer directory.
   * @return the temporary path
   */
  private def temporaryPath: Path = FileSystems.getDefault.getPath(System getProperty PropTempDir)

  /**
   * A message that tells the ''LocalBufferActor'' to fill its buffer with the
   * content read from the contained ''FileReaderActor''.
   *
   * In reaction of this message, the buffer actor reads the content of the
   * file pointed to by the passed in actor and stores it in a temporary
   * file. From there it can later be read again during audio playback.
   * @param readerActor the reader actor to be read
   */
  case class FillBuffer(readerActor: ActorRef)

  /**
   * A message sent by the buffer actor after the content of a reader actor
   * has been processed and written into the buffer.
   * @param readerActor the reader actor that has been read
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
   * Data is read from the buffer via a newly created ''FileReaderActor''.
   * As soon as a temporary file is ready for being read, this message is sent
   * to the original caller. The caller can then read data via the protocol
   * defined by ''FileReaderActor''. When this is done, the actor must be
   * stopped. This causes the underlying file to be marked as read and
   * removed from the buffer.
   * @param readerActor the actor for reading data from the buffer
   */
  case class BufferReadActor(readerActor: ActorRef)

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

  private class LocalBufferActorImpl(optBufferManager: Option[BufferFileManager])
    extends LocalBufferActor(optBufferManager) with ChildActorFactory

  /**
   * Creates a ''Props'' object which can be used to create new actor instances
   * of this class. This method should always be used; it guarantees that all
   * required dependencies are satisfied.
   * @param optBufferManager an option with the object for managing temporary files
   * @return a ''Props'' object for creating actor instances
   */
  def apply(optBufferManager: Option[BufferFileManager] = None): Props =
    Props(classOf[LocalBufferActorImpl], optBufferManager)
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
 * references a ''FileReaderActor''. This actor is used to read data until the
 * buffer is full. On the other hand, a ''FileReaderActor'' can be queried from
 * reading from the buffer.
 *
 * Filling the buffer works by reading the content of the provided reader actor
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
 * @param optBufferManager an option with the object for managing temporary files
 */
class LocalBufferActor(optBufferManager: Option[BufferFileManager]) extends Actor {
  this: ChildActorFactory =>

  import de.oliver_heger.splaya.playback.LocalBufferActor._

  /** The size of temporary files created by this buffer actor. */
  val temporaryFileSize = context.system.settings.config.getInt(PropFileSize)

  /** The chunk size for I/O operations. */
  val chunkSize = context.system.settings.config.getInt(PropChunkSize)

  /** The object for managing temporary files. */
  private[playback] lazy val bufferManager = optBufferManager getOrElse createBufferManager()

  /** The object for handling a close operation. */
  private var closingState: ClosingState = _

  /** The current reader actor for filling the buffer. */
  private var fillActor: Option[ActorRef] = None

  /** The client responsible for the current fill operation. */
  private var fillClient: ActorRef = _

  /** The current writer actor. */
  private var writerActor: ActorRef = _

  /** The current actor for reading data from the buffer. */
  private var readActor: ActorRef = _

  /** A client requesting a buffer read operation. */
  private var readClient: Option[ActorRef] = None

  /** The current temporary file which is filled by a fill operation. */
  private var currentPath: Option[Path] = None

  /** A read result which has to be processed after finishing the current write operation. */
  private var pendingReadResult: Option[ArraySource] = None

  /** The number of bytes that have been written to the current temporary file. */
  private var bytesWrittenToFile = 0

  /** The number of bytes written for the current source. */
  private var bytesWrittenForSource = 0L

  /** A flag whether a fill request is pending. */
  private var pendingFillRequest = false

  /**
   * @inheritdoc This implementation creates a write actor as a child.
   *             This actor is re-used for creating files in the buffer.
   */
  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    bufferManager.clearBufferDirectory()
    writerActor = createChildActor(Props[FileWriterActor])
  }

  override def receive: Receive = {
    case FillBuffer(actor) =>
      if (fillActor.isDefined) {
        sender ! BufferBusy
      } else {
        bytesWrittenForSource = 0
        fillActor = Some(actor)
        fillClient = sender()
        pendingFillRequest = true
        serveFillRequest()
        context watch actor
      }

    case readResult: ArraySource =>
      handleReadResult(readResult)
      bytesWrittenToFile += readResult.length
      bytesWrittenForSource += readResult.length

    case WriteResult(_, length) =>
      if (bytesWrittenToFile >= temporaryFileSize) {
        writerActor ! CloseRequest
      } else {
        continueFilling()
      }

    case CloseAck(actor) if writerActor == actor =>
      bufferManager append currentPath.get
      bytesWrittenToFile = 0
      currentPath = None
      serveReadRequest()
      continueFilling()

    case EndOfFile(_) =>
      fillOperationCompleted(sender())

    case ReadBuffer =>
      if (readClient.isDefined) {
        sender ! BufferBusy
      } else {
        readClient = Option(sender())
        serveReadRequest()
      }

    case Terminated(actor) if actor == readActor =>
      completeReadOperation()
      serveFillRequest()

    case Terminated(actor) if actor != readActor =>
      fillOperationCompleted(actor)

    case SequenceComplete =>
      if (currentPath.isDefined) {
        writerActor ! CloseRequest
      }

    case CloseRequest =>
      closingState = new ClosingState(sender())
      closingState.initiateClosing()
      context become closing
  }

  def closing: Receive = {
    case FillBuffer(readerActor) =>
      sender ! BufferBusy

    case CloseAck(actor) if writerActor == actor =>
      closingState.writeActorClosed()

    case ReadBuffer =>
      sender ! BufferBusy

    case SequenceComplete =>
      sender ! BufferBusy

    case Terminated(actor) if actor == readActor =>
      completeReadOperation()
      closingState.readActorStopped()
  }

  /**
   * Continues a fill operation. This method is called after data obtained from
   * the current fill actor was written by the writer actor. It either
   * requests the next chunk of data or processes remaining bytes to be
   * written.
   */
  private def continueFilling(): Unit = {
    pendingReadResult match {
      case Some(result) =>
        handleReadResult(result)
      case None =>
        fillActor foreach (_ ! ReadData(chunkSize))
    }
  }

  /**
   * A fill operation has been completed. The corresponding message is sent if
   * actually the fill actor is affected.
   * @param actor the actor responsible for this message
   */
  private def fillOperationCompleted(actor: ActorRef): Unit = {
    fillActor foreach { a =>
      if (actor == a) {
        fillClient ! BufferFilled(a, bytesWrittenForSource)
        fillActor = None
        context unwatch a
      }
    }
  }

  /**
   * A read operation has been completed. The temporary file can now be
   * removed from the buffer.
   */
  private def completeReadOperation(): Unit = {
    bufferManager.checkOutAndRemove()
    readClient = None
  }

  /**
   * Handles the result of a read operation. The normal processing is that the
   * data that was read is now written into the current output file. (If none
   * is open, a new one is created now.) The maximum size of the output file is
   * ensured; if necessary, the data is split, and only a part is written now.
   * The remaining data is processed after the write request completed.
   * @param readResult the object with the result of the read operation
   */
  private def handleReadResult(readResult: ArraySource): Unit = {
    val (request, pending) = currentAndPendingWriteRequest(readResult)
    ensureWriteActorInitialized() ! request
    pendingReadResult = pending
  }

  /**
   * Initializes the write actor for a new temporary file if this is necessary.
   * This method creates a new temporary file name and passes it to the write
   * actor. This action is needed whenever the maximum size of a temporary file
   * was reached and it has been closed.
   * @return a reference to the initialized write actor
   */
  private def ensureWriteActorInitialized(): ActorRef = {
    if (currentPath.isEmpty) {
      currentPath = Some(bufferManager.createPath())
      writerActor ! InitFile(currentPath.get)
    }
    writerActor
  }

  /**
   * Determines the current and the pending write request based on the passed
   * in read result. This method checks whether the data of the passed in
   * result object still fits into the current temporary file. If this is not
   * the case, the request has to be split into two.
   * @param readResult the read result
   * @return a tuple with the current and the pending write request
   */
  private def currentAndPendingWriteRequest(readResult: ArraySource): (ArraySource,
    Option[ArraySource]) = {
    if (readResult.length + bytesWrittenToFile <= temporaryFileSize) (readResult, None)
    else {
      val actLength = temporaryFileSize - bytesWrittenToFile
      (new ArraySourceImpl(readResult, length = actLength),
        Some(ArraySourceImpl(readResult, actLength)))
    }
  }

  /**
   * Checks whether a fill request is currently pending and can be served.
   * This method checks whether all criteria are fulfilled to start a new
   * fill operation. It is called when conditions change that might affect
   * whether a fill operation is possible or not.
   */
  private def serveFillRequest(): Unit = {
    if (pendingFillRequest && !bufferManager.isFull) {
      fillActor.get ! ReadData(chunkSize)
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
    for {client <- readClient
         path <- bufferManager.read} {
      readActor = createChildActor(Props[FileReaderActor])
      context watch readActor
      readActor ! InitFile(path)
      client ! BufferReadActor(readActor)
    }
  }

  /**
   * Creates a default buffer manager. This method is called if no buffer manager
   * was passed to the constructor.
   * @return the default buffer manager
   */
  private def createBufferManager(): BufferFileManager =
    new BufferFileManager(temporaryPath, context.system.settings.config.getString(PropFilePrefix),
      context.system.settings.config.getString(PropFileExtension))

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
     * @return a flag whether this actor could be closed directly
     */
    def initiateClosing(): Boolean = {
      readActorPending = readClient.isDefined
      writeActorPending = currentPath.isDefined
      if (writeActorPending) {
        writerActor ! CloseRequest
      }
      closeIfPossible()
    }

    /**
     * Notifies this object that the write actor was closed. This may impact an
     * ongoing closing operation. If closing is in progress and now all
     * conditions are fulfilled, this actor is closed. The return value
     * indicates whether the caller can continue its current operation
     * ('''true''') or whether it should be aborted ('''false''').
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
     * @return a flag whether the current operation can be continued
     */
    def readActorStopped(): Boolean = {
      readActorPending = false
      closeIfPossible()
    }

    /**
     * Closes this actor if all conditions are fulfilled.
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
