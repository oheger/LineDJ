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

import java.nio.file.{Path, Paths}

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.pattern.pipe
import akka.util.Timeout
import de.oliver_heger.linedj.io.{RemoveFileActor, ScanResult}
import de.oliver_heger.linedj.platform.app.ClientApplication
import de.oliver_heger.linedj.platform.audio.model.SongData
import de.oliver_heger.linedj.platform.mediaifc.{MediaActors, MediaFacade}
import de.oliver_heger.linedj.shared.archive.media.MediumFileRequest
import de.oliver_heger.linedj.utils.ChildActorFactory

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

object ExportActor {

  /**
   * An enumeration for describing the possible operations executed during an
   * export.
   *
   * The constants defined here are mainly intended to give feedback about the
   * operation currently executed. For instance, if an operation fails, it can
   * be determined what happened, so that the user can be given a meaningful
   * error message.
   */
  object OperationType extends Enumeration {
    /**
     * Type for a remove operation. This means that a specific file is removed
     * from the target medium.
     */
    val Remove: OperationType.Value = Value

    /**
     * Type for a copy operation. This means that a specific file is downloaded
     * from the server and copied into the target medium.
     */
    val Copy: OperationType.Value = Value
  }

  /**
   * A message processed by [[ExportActor]] that defines a full export
   * operation.
   *
   * An instance contains all the files to be exported plus some additional
   * meta which impacts the operation. For instance, it can be specified
   * whether the target medium is to be cleaned before the export or whether
   * files existing on the medium are to be overridden.
   *
   * @param songs the songs to be exported
   * @param targetContent the content of the target medium
   * @param exportPath the path where songs are exported
   * @param clearTarget a flag whether the target medium is to be cleaned
   * @param overrideFiles a flag whether files on the target medium are to be
   *                      overridden if they exist
   */
  case class ExportData(songs: Seq[SongData], targetContent: ScanResult, exportPath: Path,
                        clearTarget: Boolean, overrideFiles: Boolean)

  /**
   * A message processed by [[ExportActor]] which cancels the current export
   * operation.
   *
   * When this message is received, the currently executed operation is
   * canceled if necessary. Eventually, an [[ExportResult]] message is sent.
   */
  case object CancelExport

  /**
   * A data class storing information about an error that occurred during an
   * export operation.
   *
   * @param errorPath the path of the file that caused the error
   * @param errorType the type of the error (delete, copy)
   */
  case class ExportError(errorPath: Path, errorType: OperationType.Value)

  /**
   * A message sent by [[ExportActor]] after an export operation is done.
   *
   * The message can be used to find out whether the export was successful. If
   * not, information about the error is available.
   *
   * @param error an option for an error which caused the export to fail
   */
  case class ExportResult(error: Option[ExportError])

  /**
   * A message sent by [[ExportActor]] periodically during an export operation
   * to give feedback about the progress. The information provided here can be
   * used for instance to display a progress indicator.
   *
   * @param totalOperations the total number of export operations
   * @param totalSize the total size to be copied (in bytes)
   * @param currentOperation the index of the current operation
   * @param currentSize the number of bytes that have been copied so far
   * @param currentPath the path which is currently processed
   * @param operationType the type of the operation currently executed
   */
  case class ExportProgress(totalOperations: Int, totalSize: Long, currentOperation: Int,
                            currentSize: Long, currentPath: Path, operationType: OperationType
  .Value)

  /**
    * An internally used message class the actor sends to itself when the
    * future for fetching the media manager actor is fulfilled.
    *
    * @param manager the media manager actor
    */
  private [export] case class MediaManagerFetched(manager: ActorRef)

  private class ExportActorImpl(mediaFacade: MediaFacade, chunkSize: Int, progressSize: Int)
    extends ExportActor(mediaFacade, chunkSize, progressSize) with ChildActorFactory

  /**
    * A special instance of an ''ExportResult'' that represents an error during
    * initialization. Such an error is not related to a file operation;
    * therefore, the corresponding properties are undefined. This instance is
    * published on the message bus if the export operation cannot be started.
    */
  val InitializationError = ExportResult(Some(ExportError(null, null)))

  /**
    * Constant representing a successful result. This instance is published on
    * the message bus if the export operation has been successful.
    */
  val ResultSuccess = ExportResult(error = None)

  /** Timeout to be used when fetching the media manager actor. */
  private [export] implicit val FetchActorTimeout: Timeout = Timeout(10.seconds)

  /**
   * Returns a ''Props'' object for creating an instance of this actor class.
   * @param mediaFacade the facade to the media archive
   * @param chunkSize chunks size of copy operations
   * @param progressSize size for sending progress notifications
   * @return creation properties for an actor instance
   */
  def apply(mediaFacade: MediaFacade, chunkSize: Int, progressSize: Int): Props =
    Props(classOf[ExportActorImpl], mediaFacade, chunkSize, progressSize)

  /** An expression defining illegal characters in a song title. */
  private val InvalidCharacters = "[:*?\"<>\t|/\\\\]".r

  /**
   * Initializes data for the export operation based on the given data
   * object. This method mainly determines the operations to be executed during
   * the export.
   * @param data the data object describing the export
   * @return the export operations and the size of the files to be copied
   */
  private[export] def initializeExportData(data: ExportData): (Seq[ExportOperation], Long) = {
    val targetPaths = generateTargetPaths(data.exportPath, data.songs)
    val copySongs = filterSongsNotToBeCopied(data, targetPaths)
    (createOperations(data, copySongs),calculateTotalSize(copySongs.unzip._1))
  }

  /**
   * Creates a list with the operations to be executed during an export. The
   * list is generated based on the passed in data. The data is inspected to
   * find out which files are to be removed or copied. Corresponding operation
   * objects are then created which are processed during the actual export.
   * @param data the data object describing the export
   * @param copySongs the songs to be copied and their target paths
   * @return a sequence with the single export operations
   */
  private def createOperations(data: ExportData, copySongs: Seq[(SongData, Path)])
  : Seq[ExportOperation] = {
    val buffer = if (data.clearTarget) createRemoveOperations(data.targetContent)
    else ListBuffer.empty[ExportOperation]
    val copyOps = copySongs map { s => CopyOperation(s._1, s._2) }
    buffer ++= copyOps

    buffer.toList
  }

  /**
   * Calculates the total number of bytes to be copied in an export operation.
   * @param songs the list with the songs to be copied
   * @return the total size of this export
   */
  private def calculateTotalSize(songs: Seq[SongData]): Long =
    songs.foldLeft(0L) { (sz, song) => sz + song.metaData.size }

  /**
   * Returns a list with the songs that need to be copied. If overriding of
   * songs is disabled, songs already existing on the target medium must not be
   * copied.
   * @param data the export data object
   * @param songPaths the paths for the songs to be copied
   * @return the list of songs to be copied and their target paths
   */
  private def filterSongsNotToBeCopied(data: ExportData, songPaths: Seq[Path]): Seq[(SongData,
    Path)] = {
    val songsWithPath = data.songs.zip(songPaths)
    if (data.clearTarget || data.overrideFiles) songsWithPath
    else {
      val existingPaths = Map(data.targetContent.files.map(f => (f.path, f.size)): _*)
      songsWithPath.filterNot(s => existingPaths.contains(s._2.toString) &&
        existingPaths(s._2.toString) == s._1.metaData.size)
    }
  }

  /**
   * Creates a list buffer with operations which clear the target medium.
   * @param scanResult the scan result
   * @return the buffer with remove operations
   */
  private def createRemoveOperations(scanResult: ScanResult) : ListBuffer[ExportOperation] = {
    val buffer = ListBuffer.empty[ExportOperation]
    buffer ++= scanResult.files.map(f => RemoveOperation(Paths get f.path))
    if (scanResult.directories.nonEmpty) {
      // The first directory is the output root directory
      buffer ++= scanResult.directories.tail.reverse.map(RemoveOperation)
    }
    buffer
  }

  /**
   * Generates a sequence with the paths for storing the songs to be copied.
   * @param exportPath the export path
   * @param songs the list of songs to be copied
   * @return a list with the corresponding target paths
   */
  private def generateTargetPaths(exportPath: Path, songs: Seq[SongData]): Seq[Path] = {
    val digits = math.log10(math.max(songs.length, 100)).toInt + 1
    songs.zipWithIndex map (s => exportPath.resolve(generateTargetFileName(s, digits)))
  }

  /**
   * Generates the name of a song file on the target medium.
   * @param s the data of the affected song
   * @param digits the number of digits for the song index
   * @return the song name
   */
  private def generateTargetFileName(s: (SongData, Int), digits: Int): String =
    s"${formatIndex(s._2, digits)} - ${validTitle(s._1.getTitle)}${extractExtension(s._1.id.uri)}"

  /**
   * Generates a song title that contains only valid characters to be used in
   * file names.
   * @param title the song title
   * @return the validated title; all invalid characters have been replaced by
   *         a '_' character
   */
  private def validTitle(title: String): String = InvalidCharacters.replaceAllIn(title, "_")

  /**
   * Extracts the file extension from the given URI.
   * @param uri the URI
   * @return the extension
   */
  private def extractExtension(uri: String): String = {
    val dot = uri lastIndexOf '.'
    if (dot > 0) uri substring dot
    else ""
  }

  /**
   * Generates the index of a song in the exported list with the given number
   * of digits. The number of digits is calculated based on the number of
   * songs.
   * @param index the index
   * @param digits the number of digits
   * @return the formatted song index
   */
  private def formatIndex(index: Int, digits: Int): String = {
    val sIdx = (index + 1).toString
    if (sIdx.length >= digits) sIdx
    else "".padTo(digits - sIdx.length, '0') + sIdx
  }
}

/**
 * An actor class which exports a playlist.
 *
 * An instance of this actor class is created every time a playlist is to be
 * exported. The actor is sent a message with the files to be exported and a
 * list of the files to be removed on the target medium (in case the user wants
 * to clean the target medium first). There are two child actors for doing the
 * removal and the export of single files. They are triggered for each file
 * affected by the export operation. When the export is done a confirmation
 * message is sent to the message bus.
 *
 * It is possible to cancel an export at any time. If a copy operation is
 * currently pending, it is aborted. In any case, a confirmation message is
 * published on the message bus. This is also done in case of an error so that
 * it is possible to give feedback to the user.
 *
 * @param mediaFacade the facade to the media archive
 * @param chunkSize chunks size of copy operations
 * @param progressSize size for sending progress notifications
 */
class ExportActor(mediaFacade: MediaFacade, chunkSize: Int, progressSize: Int)
  extends Actor with ActorLogging {
  this: ChildActorFactory =>

  import ExportActor._

  /** The data for the current export operation. */
  private var exportData: ExportData = _

  /** The remove file actor. */
  private var removeFileActor: ActorRef = _

  /** The reference to copy file actor. */
  private var copyFileActor: ActorRef = _

  /** The operations to be performed during the current export. */
  private var exportOperations: Seq[ExportOperation] = _

  /** The operation which is currently in progress. */
  private var currentOperation: ExportOperation = _

  /** The total number of export operations. */
  private var totalExportOperations = 0

  /** The index of the current export operation. */
  private var currentOperationIndex = 0

  /** The total size of the files to be copied. */
  private var totalSize = 0L

  /** The current number of bytes copied. */
  private var currentSize = 0L

  /**
   * @inheritdoc This implementation creates the child actors and sends a
   *             request for the media manager remote actor.
   */
  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()

    import context.dispatcher
    mediaFacade.requestActor(MediaActors.MediaManager).map(o =>
      MediaManagerFetched(o.get)) pipeTo self

    removeFileActor = createChildActor(Props[RemoveFileActor]
      .withDispatcher(ClientApplication.BlockingDispatcherName))
    context watch removeFileActor
  }

  /**
   * The supervisor strategy. Here all failing children are stopped. An error
   * in a child actor means a failed operation. This causes the export to be
   * aborted.
   */
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: Exception => Stop
  }

  override def receive: Receive = prepareReceive

  /**
   * Returns a ''Receive'' function which is active during the preparation of
   * an export operation. In this phase, the required data is collected, then
   * the export is actually started.
   * @return the function for handling messages in the preparation phase
   */
  private def prepareReceive: Receive = {
    case MediaManagerFetched(actor) =>
      copyFileActor = createChildActor(CopyFileActor(self, actor, chunkSize, progressSize)
        .withDispatcher(ClientApplication.BlockingDispatcherName))
      context watch copyFileActor
      startExportIfPossible()

    case Status.Failure(err) =>
      log.error(err, "Could not obtain media manager actor!")
      completeExport(InitializationError)

    case data: ExportData =>
      val (ops, size) = initializeExportData(data)
      exportOperations = ops
      totalSize = size
      totalExportOperations = ops.size
      exportData = data
      startExportIfPossible()

    case CancelExport =>
      completeExport()
  }

  /**
   * Returns a ''Receive'' function which is active during an export.
   * @return the function for handling messages during a running export
   */
  private def exportReceive: Receive = {
    case RemoveFileActor.FileRemoved(path) if checkCurrentOperation(OperationType.Remove, path) =>
      sendFeedbackAndContinueExport()

    case CopyFileActor.MediumFileCopied(_, path) if checkCurrentOperation(OperationType.Copy,
      path) =>
      sendFeedbackAndContinueExport()

    case CopyFileActor.CopyProgress(req, size) if checkCurrentOperation(OperationType.Copy, req
      .target) =>
      publish(createProgressMessage(currentSize + size, currentOperationIndex + 1))

    case CancelExport =>
      // This can always be sent; if no operation is ongoing, it is ignored
      copyFileActor ! CopyFileActor.CancelCopyOperation
      exportOperations = Nil

    case _: Terminated =>
      completeExport(ExportResult(Some(ExportError(currentOperation.affectedPath,
        currentOperation.operationType))))
  }

  /**
   * Starts the export operation if all required data is available.
   */
  private def startExportIfPossible(): Unit = {
    if (exportData != null && copyFileActor != null) {
      continueExport()
      context become exportReceive
    }
  }

  /**
   * Sends a feedback message about the current state of the export operation
   * via the message bus and then continues the export.
   */
  private def sendFeedbackAndContinueExport(): Unit = {
    currentSize += currentOperation.processedSize
    currentOperationIndex += 1
    publish(createProgressMessage(currentSize, currentOperationIndex))
    continueExport()
  }

  /**
   * Creates a message about the progress of the current export operation.
   * @param progressSize the size currently processed
   * @param progressOpIdx the index of the current operation
   * @return the progress message
   */
  private def createProgressMessage(progressSize: Long, progressOpIdx: Int): ExportProgress = {
    ExportProgress(totalOperations = totalExportOperations,
      totalSize = totalSize, currentOperation = progressOpIdx, currentSize = progressSize,
      currentPath = currentOperation.affectedPath, operationType = currentOperation.operationType)
  }

  /**
   * Invokes the next operation in the current export.
   */
  private def continueExport(): Unit = {
    def invoke(actor: ActorRef, msg: Option[Any]): Unit = {
      msg.foreach(actor ! _)
    }

    if (exportOperations.isEmpty) {
      completeExport()
    } else {
      val op = exportOperations.head
      invoke(removeFileActor, op.getRemoveMessage)
      invoke(copyFileActor, op.getCopyMessage)
      exportOperations = exportOperations.tail
      currentOperation = op
    }
  }

  /**
    * Performs actions for completing an export operation. This method is called
    * after everything is done (either successful or after an error).
    *
    * @param result the result message to publish on the message bus
    */
  private def completeExport(result: ExportResult = ResultSuccess): Unit = {
    publish(result)
    context become Actor.emptyBehavior
  }

  /**
   * Checks whether the current operation has the specified properties. This is
   * used to check whether a response message received from a child actor
   * matches the current operation.
   * @param operationType the operation type
   * @param path the affected path
   * @return a flag whether the current operation matches these properties
   */
  private def checkCurrentOperation(operationType: OperationType.Value, path: Path): Boolean =
    currentOperation.operationType == operationType && currentOperation.affectedPath == path

  /**
   * Publishes the specified message on the message bus.
   * @param msg the message to be published
   */
  private def publish(msg: Any): Unit = {
    mediaFacade.bus publish msg
  }
}

/**
 * A trait defining a single (file-based) operation that is done during an
 * export.
 *
 * An export involves downloading and copying some files and - optionally -
 * deleting files on the target medium. This trait represents such an
 * operation. Before an export actually starts, a list of such operations is
 * generated. This is then processed by the export actor step by step.
 */
private trait ExportOperation {
  /**
   * The path which is affected by this operation (on the target medium). This
   * path can be displayed to the end user, e.g. as a feedback what is
   * currently processed or which operation failed.
   */
  val affectedPath: Path

  /**
   * The type of this operation. This is additional meta information about the
   * represented operation.
   */
  val operationType: ExportActor.OperationType.Value

  /**
   * Returns the message to be sent to the remove actor if any.
   * @return the optional message to the remove actor
   */
  def getRemoveMessage: Option[Any] = None

  /**
   * Returns the message to be sent to the copy actor if any.
   * @return the optional message to the copy actor
   */
  def getCopyMessage: Option[Any] = None

  /**
   * Returns the size (in bytes) processed by this operation. This is used for
   * generating feedback messages; so the user can be informed about the number
   * of bytes which has already been copied.
   * @return the number of bytes processed by this operation
   */
  def processedSize: Long
}

/**
 * A specialized export operation for downloading and copying a file.
 *
 * @param affectedSong the data about the song to be copied
 * @param affectedPath the target path
 */
private case class CopyOperation(affectedSong: SongData, override val affectedPath: Path) extends
ExportOperation {
  override val operationType = ExportActor.OperationType.Copy

  override def getCopyMessage: Option[Any] =
    Some(CopyFileActor.CopyMediumFile(MediumFileRequest(affectedSong.id,
      withMetaData = true), affectedPath))

  /**
   * @inheritdoc This implementation returns the size of the affected file.
   */
  override def processedSize: Long = affectedSong.metaData.size
}

/**
 * A specialized export operation for removing a file on the target medium.
 *
 * @param affectedPath the path to be removed
 */
private case class RemoveOperation(override val affectedPath: Path) extends ExportOperation {
  override val operationType = ExportActor.OperationType.Remove

  override def getRemoveMessage: Option[Any] =
    Some(RemoveFileActor.RemoveFile(affectedPath))

  /**
   * @inheritdoc A remove operation does not process bytes; therefore, this
   *             implementation always returns 0.
   */
  override def processedSize: Long = 0
}
